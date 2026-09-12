"""Local-only regression tests; no paid provider calls or credentials required."""
import base64
import io
import json
import threading
import unittest
import urllib.error
import urllib.request
import zipfile
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from unittest.mock import patch

try:
    import providers
    import image_tools
    import server
except ImportError:
    providers = image_tools = server = None


class BackendTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.calls = []
        class Upstream(BaseHTTPRequestHandler):
            def do_POST(self):
                raw = self.rfile.read(int(self.headers.get('Content-Length', 0)))
                cls.calls.append((self.path, dict(self.headers), raw))
                if self.path == '/error':
                    self.send_response(401)
                    payload = {'error': {'message': 'rejected SECRET_TOKEN'}}
                elif self.path.endswith('/responses'):
                    self.send_response(200)
                    payload = {'output': [{'type': 'reasoning', 'id': 'rs_1', 'summary': []}, {'type': 'function_call', 'call_id': 'c1', 'name': 'preview', 'arguments': '{}'}], 'usage': {'input_tokens': 4}}
                elif self.path.endswith('/messages'):
                    self.send_response(200)
                    payload = {'content': [{'type': 'text', 'text': 'Hello'}, {'type': 'tool_use', 'id': 'c1', 'name': 'preview', 'input': {}}], 'usage': {'input_tokens': 4}}
                elif '/images/' in self.path:
                    self.send_response(200)
                    payload = {'data': [{'b64_json': cls.png().split(',')[1]}]}
                elif self.path.endswith('/search'):
                    self.send_response(200)
                    payload = {'results': [{'title': 'Source', 'url': 'https://example.com', 'content': 'Result'}], 'images': ['https://example.com/test.png']}
                else:
                    self.send_response(200)
                    payload = {'choices': [{'message': {'content': 'Hello', 'tool_calls': [{'id': 'c1', 'type': 'function', 'function': {'name': 'preview', 'arguments': '{}'}}]}}], 'usage': {'prompt_tokens': 4}}
                self.send_header('Content-Type', 'application/json')
                self.end_headers()
                self.wfile.write(json.dumps(payload).encode())
            def log_message(self, *_args):
                pass
        cls.upstream = ThreadingHTTPServer(('127.0.0.1', 0), Upstream)
        cls.thread = threading.Thread(target=cls.upstream.serve_forever, daemon=True)
        cls.thread.start()
        cls.base = 'http://127.0.0.1:' + str(cls.upstream.server_port)

    @classmethod
    def tearDownClass(cls):
        cls.upstream.shutdown()
        cls.upstream.server_close()

    @staticmethod
    def png():
        from PIL import Image
        buf = io.BytesIO()
        Image.new('RGBA', (8, 8), '#ff8800').save(buf, 'PNG')
        return 'data:image/png;base64,' + base64.b64encode(buf.getvalue()).decode()

    def setUp(self):
        self.assertIsNotNone(providers, 'The backend implementation is required')

    def request(self, protocol='chat', **extra):
        request = {'config': {'protocol': protocol, 'baseUrl': self.base + '/v1', 'model': 'test-model', 'apiKey': 'SECRET_TOKEN'}, 'system': 'Guide', 'messages': [{'role': 'user', 'content': 'Draw'}], 'tools': [{'name': 'preview', 'description': 'Preview', 'parameters': {'type': 'object', 'properties': {}}}]}
        request.update(extra)
        return request

    def test_endpoint_normalization(self):
        for protocol, suffix in [('chat', 'chat/completions'), ('responses', 'responses'), ('claude', 'messages')]:
            self.assertEqual(providers.endpoint(self.base + '/v1', protocol), self.base + '/v1/' + suffix)
            self.assertEqual(providers.endpoint(self.base + '/v1/' + suffix, protocol), self.base + '/v1/' + suffix)
        self.assertEqual(providers.endpoint(self.base, 'chat'), self.base + '/v1/chat/completions')
        self.assertEqual(providers.endpoint(self.base + '/images/generations', 'image_edit'), self.base + '/images/edits')

    def test_chat_roundtrip(self):
        result = providers.chat(self.request())
        self.assertEqual(result['text'], 'Hello')
        self.assertEqual(result['toolCalls'][0], {'id': 'c1', 'name': 'preview', 'arguments': {}})
        path, headers, raw = self.calls[-1]
        body = json.loads(raw)
        self.assertEqual(path, '/v1/chat/completions')
        self.assertEqual(body['messages'][0], {'role': 'system', 'content': 'Guide'})
        self.assertEqual(headers['Authorization'], 'Bearer SECRET_TOKEN')
        self.assertEqual(body['tools'][0]['function']['name'], 'preview')

    def test_optional_parallel_tool_calls_protocol_parameters(self):
        for protocol in ('chat', 'responses'):
            with self.subTest(protocol=protocol, setting='omitted'):
                providers.chat(self.request(protocol))
                self.assertNotIn('parallel_tool_calls', json.loads(self.calls[-1][2]))
            for enabled in (True, False):
                with self.subTest(protocol=protocol, setting=enabled):
                    request = self.request(protocol)
                    request['config']['parallelToolCalls'] = enabled
                    providers.chat(request)
                    self.assertIs(json.loads(self.calls[-1][2]).get('parallel_tool_calls'), enabled)
        request = self.request('claude')
        request['config']['parallelToolCalls'] = True
        providers.chat(request)
        self.assertNotIn('parallel_tool_calls', json.loads(self.calls[-1][2]))
        request = self.request(tools=[])
        request['config']['parallelToolCalls'] = True
        providers.chat(request)
        self.assertNotIn('parallel_tool_calls', json.loads(self.calls[-1][2]))

    def test_parallel_tool_calls_rejects_non_boolean(self):
        request = self.request()
        request['config']['parallelToolCalls'] = 'false'
        before = len(self.calls)
        with self.assertRaises(ValueError):
            providers.chat(request)
        self.assertEqual(len(self.calls), before)

    def history(self):
        return [{'role': 'user', 'content': 'Draw'}, {'role': 'assistant', 'content': '', 'toolCalls': [{'id': 'c1', 'name': 'preview', 'arguments': {}}]}, {'role': 'tool', 'content': 'Rendered', 'toolCallId': 'c1', 'images': [self.png()]}]

    def test_chat_tool_images_actual_multimodal(self):
        providers.chat(self.request(messages=self.history()))
        body = json.loads(self.calls[-1][2])
        self.assertEqual(body['messages'][-2]['role'], 'tool')
        self.assertEqual(body['messages'][-1]['content'][1]['type'], 'image_url')

    def test_chat_multiple_tool_images_follow_all_results(self):
        history = self.history()
        history[1]['toolCalls'].append({'id': 'c2', 'name': 'preview', 'arguments': {}})
        history.append({'role': 'tool', 'content': 'Second', 'toolCallId': 'c2'})
        providers.chat(self.request(messages=history))
        roles = [m['role'] for m in json.loads(self.calls[-1][2])['messages']]
        self.assertEqual(roles[-3:], ['tool', 'tool', 'user'])

    def test_responses_preserves_native_reasoning(self):
        result = providers.chat(self.request('responses'))
        self.assertEqual(result['toolCalls'][0]['id'], 'c1')
        history = self.history()
        history[1]['native'] = result['native']
        providers.chat(self.request('responses', messages=history))
        body = json.loads(self.calls[-1][2])
        self.assertIn('reasoning', [m.get('type') for m in body['input']])
        self.assertEqual(body['input'][-2]['type'], 'function_call_output')
        self.assertEqual(body['input'][-1]['content'][1]['type'], 'input_image')
        self.assertEqual(body['tools'][0]['name'], 'preview')

    def test_claude_tool_images_and_headers(self):
        result = providers.chat(self.request('claude', messages=self.history()))
        self.assertEqual(result['toolCalls'][0]['arguments'], {})
        body = json.loads(self.calls[-1][2])
        self.assertEqual(self.calls[-1][1]['X-Api-Key'], 'SECRET_TOKEN')
        self.assertEqual(self.calls[-1][1]['Anthropic-Version'], '2023-06-01')
        self.assertEqual(body['system'], 'Guide')
        self.assertEqual(body['messages'][-1]['content'][0]['type'], 'tool_result')
        self.assertEqual(body['messages'][-1]['content'][0]['content'][1]['type'], 'image')

    def test_provider_error_redacts_key_and_never_retries(self):
        request = self.request()
        request['config']['baseUrl'] = self.base + '/error'
        before = len(self.calls)
        with self.assertRaises(providers.APIError) as caught:
            providers.chat(request)
        self.assertNotIn('SECRET_TOKEN', str(caught.exception))
        self.assertEqual(len(self.calls) - before, 1)

    def test_header_injection_rejected(self):
        request = self.request()
        request['config']['headers'] = {'Authorization': 'safe\r\nInjected: yes'}
        with self.assertRaises(ValueError):
            providers.chat(request)

    def test_generate_and_edit(self):
        request = {'config': {'baseUrl': self.base + '/v1', 'apiKey': 'SECRET_TOKEN', 'model': 'image-test', 'size': '1024x1024'}, 'prompt': 'Dial'}
        result = providers.generate_image(request)
        self.assertEqual(result['width'], 8)
        self.assertEqual(json.loads(self.calls[-1][2])['prompt'], 'Dial')
        request['image'] = self.png()
        result = providers.generate_image(request)
        self.assertEqual(self.calls[-1][0], '/v1/images/edits')
        self.assertIn(b'name="image"', self.calls[-1][2])
        self.assertTrue(result['dataUrl'].startswith('data:image/png;'))

    def test_tavily_bounded_results(self):
        result = providers.search({'config': {'baseUrl': self.base, 'apiKey': 'SECRET_TOKEN'}, 'query': 'dial', 'includeImages': True})
        self.assertEqual(result['results'][0]['title'], 'Source')
        self.assertEqual(json.loads(self.calls[-1][2])['query'], 'dial')
        self.assertEqual(self.calls[-1][0], '/search')

    def test_public_image_refuses_private_addresses(self):
        for url in ['file:///etc/passwd', 'http://127.0.0.1/', 'http://[::1]/', 'http://169.254.169.254/', 'http://10.0.0.1/']:
            with self.assertRaises(ValueError, msg=url):
                providers.fetch_image(url)

    def test_gradient_pixels(self):
        result = image_tools.process({'operation': 'gradient', 'width': 8, 'height': 10, 'colors': ['#000000', '#ffffff']})
        img = image_tools.read_image(result['dataUrl'])
        self.assertEqual(img.size, (8, 10))
        self.assertEqual(img.getpixel((0, 0)), (0, 0, 0, 255))
        self.assertEqual(img.getpixel((0, 9)), (255, 255, 255, 255))

    def test_image_transformations(self):
        for args, expected in [({'operation': 'resize', 'width': 20, 'height': 12}, (20, 12)), ({'operation': 'crop', 'box': [1, 1, 6, 7]}, (5, 6)), ({'operation': 'rotate', 'degrees': 90}, (8, 8)), ({'operation': 'adjust', 'brightness': .5}, (8, 8)), ({'operation': 'remove_background', 'color': '#ff8800', 'threshold': 20}, (8, 8))]:
            result = image_tools.process(dict(image=self.png(), **args))
            self.assertEqual((result['width'], result['height']), expected)
        self.assertEqual(image_tools.read_image(result['dataUrl']).getpixel((0, 0))[3], 0)

    def test_ticks_transparent_center(self):
        result = image_tools.process({'operation': 'ticks', 'width': 128, 'height': 128, 'count': 60})
        img = image_tools.read_image(result['dataUrl'])
        self.assertEqual(img.getpixel((64, 64))[3], 0)
        self.assertTrue(img.getbbox())

    def test_image_limits_and_unknown_operations(self):
        for args in [{'operation': 'exec'}, {'operation': 'gradient', 'width': 100000}, {'operation': 'resize', 'image': 'file:///secret'}]:
            with self.assertRaises(ValueError):
                image_tools.process(args)

    def test_watch_archive_roundtrip(self):
        request = {'xml': '<Watch><Layer type="text"/></Watch>', 'script': 'function on_minute() end', 'assets': [{'name': 'images/dial.png', 'dataUrl': self.png()}, {'name': 'extra/info.bin', 'dataUrl': 'data:application/octet-stream;base64,aGVsbG8='}], 'preview': self.png()}
        archive = server.export_watch(request)
        result = server.import_watch({'data': archive['data']})
        self.assertEqual(result['xml'], request['xml'])
        self.assertEqual(result['script'], request['script'])
        self.assertIn('extra/info.bin', [a['name'] for a in result['assets']])
        with zipfile.ZipFile(io.BytesIO(base64.b64decode(archive['data']))) as z:
            self.assertIn('watch.xml', z.namelist())
            self.assertIn('scripts/script.txt', z.namelist())
            self.assertIn('preview.jpg', z.namelist())
            self.assertEqual(z.read('preview.jpg')[:2], b'\xff\xd8')

    def test_watch_noncanonical_scripts_preserve_paths_and_bytes(self):
        other_scripts = {'scripts/main.lua': b'print("main")', 'script.lua': b'print("legacy")', 'main.lua': b'\xff\xfe\x00', 'scripts/helper.lua': b'function helper() end'}
        for canonical in (None, b'function on_minute() end'):
            with self.subTest(has_canonical=canonical is not None):
                buffer = io.BytesIO()
                with zipfile.ZipFile(buffer, 'w') as archive:
                    archive.writestr('watch.xml', '<Watch/>')
                    if canonical is not None:
                        archive.writestr('scripts/script.txt', canonical)
                    for name, content in other_scripts.items():
                        archive.writestr(name, content)
                imported = server.import_watch({'data': base64.b64encode(buffer.getvalue()).decode()})
                self.assertEqual(imported['script'], canonical.decode() if canonical else '')
                assets = {asset['name']: image_tools.decode_data_url(asset['dataUrl'])[1] for asset in imported['assets']}
                self.assertEqual(assets, other_scripts)
                exported = server.export_watch(imported)
                with zipfile.ZipFile(io.BytesIO(base64.b64decode(exported['data']))) as archive:
                    for name, content in other_scripts.items():
                        self.assertEqual(archive.read(name), content)
                    self.assertEqual('scripts/script.txt' in archive.namelist(), canonical is not None)

    def test_watch_traversal_rejected(self):
        for name in ['../evil', '/evil', 'images/../../evil', 'C:/evil', 'images\\evil', 'images//evil']:
            buf = io.BytesIO()
            with zipfile.ZipFile(buf, 'w') as z:
                z.writestr('watch.xml', '<Watch/>')
                z.writestr(name.replace('\\', '!'), 'bad')
            with self.assertRaises(ValueError, msg=name):
                server.import_watch({'data': base64.b64encode(buf.getvalue().replace(b'images!evil', b'images\\evil')).decode()})

    def test_watch_xml_entity_and_layer_limits(self):
        for xml in ['<!DOCTYPE Watch [<!ENTITY hi "a">]><Watch>&hi;</Watch>', '<Other/>', '<Watch>' + '<Layer/>' * 513 + '</Watch>']:
            with self.assertRaises(ValueError):
                server.export_watch({'xml': xml, 'assets': []})

    def test_local_server_origin_host_and_runtime(self):
        local = server.create_server(0)
        thread = threading.Thread(target=local.serve_forever, daemon=True)
        thread.start()
        base = 'http://127.0.0.1:' + str(local.server_port)
        try:
            with urllib.request.urlopen(base + '/api/runtime') as response:
                data = json.load(response)
                self.assertIn('gradient', data['operations'])
            for headers in [{'Host': 'evil.test'}, {'Origin': 'https://evil.test'}]:
                req = urllib.request.Request(base + '/api/runtime', headers=headers)
                with self.assertRaises(urllib.error.HTTPError) as caught:
                    urllib.request.urlopen(req)
                self.assertEqual(caught.exception.code, 403)
                caught.exception.close()
            req = urllib.request.Request(base + '/api/image-process', data=b'{}', headers={'Content-Type': 'text/plain'})
            with self.assertRaises(urllib.error.HTTPError) as caught:
                urllib.request.urlopen(req)
            self.assertEqual(caught.exception.code, 415)
            caught.exception.close()
            for payload in [[], {'config': []}, {'config': {'headers': []}}]:
                req = urllib.request.Request(base + '/api/chat', data=json.dumps(payload).encode(), headers={'Content-Type': 'application/json'})
                with self.assertRaises(urllib.error.HTTPError) as caught:
                    urllib.request.urlopen(req)
                self.assertEqual(caught.exception.code, 400)
                caught.exception.close()
        finally:
            local.shutdown()
            local.server_close()


if __name__ == '__main__':
    unittest.main(verbosity=2)
