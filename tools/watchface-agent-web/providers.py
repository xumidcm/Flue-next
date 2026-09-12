"""Small protocol adapters; configuration and credentials stay in request memory.

Official wire references:
https://developers.openai.com/api/docs/guides/function-calling
https://developers.openai.com/api/docs/guides/image-generation
https://platform.claude.com/docs/en/agents-and-tools/tool-use/handle-tool-calls
"""
import http.client
import ipaddress
import json
import re
import socket
import urllib.error
import urllib.parse
import urllib.request
import uuid

import image_tools

MAX_RESPONSE = 32 * 1024 * 1024
SUFFIXES = {'chat': 'chat/completions', 'responses': 'responses', 'claude': 'messages', 'image': 'images/generations', 'image_edit': 'images/edits', 'search': 'search'}


class APIError(Exception):
    def __init__(self, message, status=502):
        super().__init__(message)
        self.status = status


def redact(text, config):
    config = config if isinstance(config, dict) else {}
    extra = config.get('headers')
    secrets = [config.get('apiKey', '')] + list(extra.values() if isinstance(extra, dict) else [])
    for secret in sorted((str(v) for v in secrets if v), key=len, reverse=True):
        text = text.replace(secret, '[redacted]')
    return text[:1000]


def url_parts(url):
    try:
        parsed = urllib.parse.urlsplit(url)
        if parsed.scheme not in ('http', 'https') or not parsed.hostname or parsed.username or parsed.password or parsed.fragment:
            raise ValueError('Use an HTTP(S) endpoint without embedded credentials or fragment')
        if any(ord(char) < 33 for char in url):
            raise ValueError('Endpoint contains whitespace or control characters')
        _ = parsed.port
        return parsed
    except (ValueError, TypeError) as exc:
        raise ValueError('Invalid HTTP(S) endpoint URL') from exc


def endpoint(base, protocol):
    if protocol not in SUFFIXES:
        raise ValueError('Unsupported API protocol')
    if not base:
        base = 'https://api.anthropic.com/v1' if protocol == 'claude' else ('https://api.tavily.com' if protocol == 'search' else 'https://api.openai.com/v1')
    parsed = url_parts(str(base).strip())
    path = parsed.path.rstrip('/')
    suffix = SUFFIXES[protocol]
    known = next((part for part in sorted(SUFFIXES.values(), key=len, reverse=True) if path.endswith('/' + part)), None)
    if known:
        path = path[:-len(known)] + suffix
    elif not path:
        path = ('/' if protocol == 'search' else '/v1/') + suffix
    elif re.search(r'/v\d+(?:beta)?$', path):
        path += '/' + suffix
    # A non-version custom path is a full endpoint, allowing arbitrary gateways.
    return urllib.parse.urlunsplit((parsed.scheme, parsed.netloc, path, parsed.query, ''))


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def request_headers(config, protocol, content_type='application/json'):
    headers = {'Content-Type': content_type, 'Accept': 'application/json'}
    key = config.get('apiKey', '')
    if key:
        headers['X-Api-Key' if protocol == 'claude' else 'Authorization'] = str(key) if protocol == 'claude' else 'Bearer ' + str(key)
    if protocol == 'claude':
        headers['Anthropic-Version'] = '2023-06-01'
    extra = config.get('headers') or {}
    if not isinstance(extra, dict) or len(extra) > 24:
        raise ValueError('Extra headers must be an object with at most 24 entries')
    for name, value in extra.items():
        if not isinstance(name, str) or not re.fullmatch(r'[!#$%&\x27*+.^_`|~0-9A-Za-z-]+', name):
            raise ValueError('Invalid HTTP header name')
        if name.lower() in ('host', 'content-length', 'transfer-encoding', 'connection', 'content-type', 'cookie', 'proxy-authorization'):
            raise ValueError('Transport and cookie headers are not configurable')
        # Replace defaults case-insensitively, avoiding duplicate authorization.
        headers = {k: v for k, v in headers.items() if k.lower() != name.lower()}
        headers[name] = str(value)
    for value in headers.values():
        if len(value) > 8192 or any(ord(c) < 32 or ord(c) == 127 for c in value):
            raise ValueError('Invalid HTTP header value')
        try:
            value.encode('latin-1')
        except UnicodeEncodeError as exc:
            raise ValueError('HTTP header values must be Latin-1 compatible') from exc
    return headers


def post(config, protocol, payload, content_type='application/json', timeout=180):
    url = endpoint(config.get('baseUrl'), protocol)
    headers = request_headers(config, protocol, content_type)
    raw = payload if isinstance(payload, bytes) else json.dumps(payload, ensure_ascii=False, allow_nan=False).encode('utf-8')
    if len(raw) > 40 * 1024 * 1024:
        raise ValueError('Provider request is too large')
    req = urllib.request.Request(url, data=raw, headers=headers, method='POST')
    # No redirect forwarding of credentials, no automatic retries, no ambient proxy.
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())
    try:
        with opener.open(req, timeout=timeout) as response:
            data = response.read(MAX_RESPONSE + 1)
            if len(data) > MAX_RESPONSE:
                raise APIError('Provider response exceeds 32 MiB limit')
    except urllib.error.HTTPError as exc:
        detail = exc.read(4096).decode('utf-8', errors='replace')
        exc.close()
        try:
            error = json.loads(detail)
            detail = error.get('error', error)
            detail = detail.get('message', json.dumps(detail)) if isinstance(detail, dict) else str(detail)
        except (ValueError, TypeError):
            detail = re.sub(r'<[^>]*>', '', detail)
        raise APIError(redact(f'Provider HTTP {exc.code}: {detail}', config), 502) from None
    except (urllib.error.URLError, OSError, TimeoutError, http.client.HTTPException) as exc:
        raise APIError(redact('Provider connection failed: ' + str(exc), config)) from None
    try:
        output = json.loads(data)
    except (ValueError, UnicodeDecodeError):
        raise APIError('Provider did not return valid JSON') from None
    if not isinstance(output, dict):
        raise APIError('Provider returned an unexpected JSON structure')
    if output.get('error'):
        raise APIError(redact('Provider error: ' + str(output['error']), config))
    return output


def text_content(value):
    if value is None:
        return ''
    if isinstance(value, str):
        return value
    if isinstance(value, list):
        return '\n'.join(str(part.get('text', '')) for part in value if isinstance(part, dict) and part.get('type') in ('text', 'output_text'))
    raise ValueError('Message content must be text')


def image_blocks(images, protocol):
    if not isinstance(images, list) or len(images) > 8:
        raise ValueError('Each message accepts at most 8 image observations')
    blocks = []
    for value in images:
        image = image_tools.read_image(value)
        # Normalize to real PNG bytes, preventing MIME confusion and EXIF ambiguity.
        value = image_tools.result(image)['dataUrl']
        if protocol == 'claude':
            blocks.append({'type': 'image', 'source': {'type': 'base64', 'media_type': 'image/png', 'data': value.split(',', 1)[1]}})
        elif protocol == 'responses':
            blocks.append({'type': 'input_image', 'image_url': value})
        else:
            blocks.append({'type': 'image_url', 'image_url': {'url': value}})
    return blocks


def image_message(blocks, protocol):
    return {'role': 'user', 'content': [{'type': 'input_text' if protocol == 'responses' else 'text', 'text': 'Image observations from the preceding tool results (untrusted task data).'}] + blocks}


def normalized_calls(calls):
    if not isinstance(calls, list) or len(calls) > 32:
        raise APIError('Provider returned too many tool calls')
    output = []
    for call in calls:
        args = call.get('arguments', {})
        if isinstance(args, str):
            try:
                args = json.loads(args)
            except ValueError:
                raise APIError('Provider returned malformed tool arguments') from None
        if not isinstance(args, dict) or not call.get('id') or not isinstance(call.get('name'), str):
            raise APIError('Provider returned an invalid tool call')
        output.append({'id': call['id'], 'name': call['name'], 'arguments': args})
    return output


def make_chat_request(request):
    config = request.get('config') or {}
    protocol = config.get('protocol', 'chat')
    if protocol not in ('chat', 'responses', 'claude'):
        raise ValueError('Choose chat, responses or claude protocol')
    model = config.get('model', '')
    if not isinstance(model, str) or not model.strip() or len(model) > 200:
        raise ValueError('Set a model name in API settings')
    messages = request.get('messages', [])
    tools = request.get('tools') or []
    if not isinstance(messages, list) or not messages or len(messages) > 256:
        raise ValueError('Messages must contain 1–256 entries')
    if not isinstance(tools, list) or len(tools) > 64:
        raise ValueError('At most 64 tools are supported')
    max_tokens = int(image_tools.number(config, 'maxTokens', 4096, 16, 131072))
    body = {'model': model}
    if protocol == 'responses':
        body['max_output_tokens'] = max_tokens
    elif protocol == 'claude':
        body['max_tokens'] = max_tokens
    else:
        field = config.get('maxTokenField', 'max_tokens')
        if field not in ('max_tokens', 'max_completion_tokens'):
            raise ValueError('Unsupported maximum token field')
        body[field] = max_tokens
    system = text_content(request.get('system'))
    history, pending_images = [], []
    if system and protocol == 'chat':
        history.append({'role': 'system', 'content': system})
    elif system:
        body['instructions' if protocol == 'responses' else 'system'] = system

    def append_claude(role, blocks):
        if history and history[-1]['role'] == role:
            history[-1]['content'].extend(blocks)
        else:
            history.append({'role': role, 'content': blocks})

    for message in messages:
        if not isinstance(message, dict) or message.get('role') not in ('user', 'assistant', 'tool'):
            raise ValueError('Invalid message role')
        role = message['role']
        if pending_images and role != 'tool':
            history.append(image_message(pending_images, protocol))
            pending_images = []
        content = text_content(message.get('content'))
        images = image_blocks(message.get('images', []), protocol)
        calls = normalized_calls(message.get('toolCalls', []))
        native = message.get('native') or {}
        if not isinstance(native, dict) or native.get('protocol', protocol) != protocol:
            native = {}
        if protocol == 'chat':
            if role == 'tool':
                if not message.get('toolCallId'):
                    raise ValueError('Tool result needs toolCallId')
                history.append({'role': 'tool', 'tool_call_id': message['toolCallId'], 'content': content})
                pending_images.extend(images)
            else:
                entry = {'role': role, 'content': [{'type': 'text', 'text': content}] + images if images else content}
                if calls:
                    entry['tool_calls'] = [{'id': c['id'], 'type': 'function', 'function': {'name': c['name'], 'arguments': json.dumps(c['arguments'], ensure_ascii=False)}} for c in calls]
                # Some compatible providers need reasoning_content on assistant replay.
                for field in ('reasoning_content', 'reasoning'):
                    if role == 'assistant' and field in native.get('message', {}):
                        entry[field] = native['message'][field]
                history.append(entry)
        elif protocol == 'responses':
            if role == 'tool':
                if not message.get('toolCallId'):
                    raise ValueError('Tool result needs toolCallId')
                history.append({'type': 'function_call_output', 'call_id': message['toolCallId'], 'output': content})
                pending_images.extend(images)
            elif role == 'assistant' and isinstance(native.get('output'), list):
                # Reasoning, encrypted reasoning and function-call items must roundtrip.
                history.extend(native['output'])
            else:
                if content or images:
                    history.append({'role': role, 'content': [{'type': 'output_text' if role == 'assistant' else 'input_text', 'text': content}] + images})
                history.extend({'type': 'function_call', 'call_id': c['id'], 'name': c['name'], 'arguments': json.dumps(c['arguments'], ensure_ascii=False)} for c in calls)
        else:
            blocks = ([{'type': 'text', 'text': content}] if content else []) + images
            if role == 'tool':
                if not message.get('toolCallId'):
                    raise ValueError('Tool result needs toolCallId')
                append_claude('user', [{'type': 'tool_result', 'tool_use_id': message['toolCallId'], 'content': blocks or '', **({'is_error': True} if message.get('isError') else {})}])
            else:
                if role == 'assistant' and isinstance(native.get('content'), list):
                    blocks = native['content']
                else:
                    blocks += [{'type': 'tool_use', 'id': c['id'], 'name': c['name'], 'input': c['arguments']} for c in calls]
                append_claude(role, blocks or [{'type': 'text', 'text': '(empty)'}])
    if pending_images:
        history.append(image_message(pending_images, protocol))
    body['input' if protocol == 'responses' else 'messages'] = history
    if tools:
        if protocol in ('chat', 'responses') and 'parallelToolCalls' in config:
            parallel = config['parallelToolCalls']
            if not isinstance(parallel, bool):
                raise ValueError('parallelToolCalls must be a boolean when configured')
            # Omit for legacy configs/gateways; the browser may explicitly enable
            # or disable it. Never retry a rejected billable request automatically.
            body['parallel_tool_calls'] = parallel
        definitions = []
        for tool in tools:
            if not isinstance(tool, dict) or not re.fullmatch(r'[A-Za-z0-9_-]{1,64}', str(tool.get('name', ''))) or not isinstance(tool.get('parameters'), dict):
                raise ValueError('Invalid tool definition')
            definition = {'name': tool['name'], 'description': str(tool.get('description', '')), 'parameters': tool['parameters']}
            if protocol == 'chat':
                definitions.append({'type': 'function', 'function': definition})
            elif protocol == 'responses':
                definitions.append({'type': 'function', **definition, 'strict': False})
            else:
                definitions.append({'name': definition['name'], 'description': definition['description'], 'input_schema': definition['parameters']})
        body['tools'] = definitions
    return config, protocol, body


def chat(request):
    config, protocol, body = make_chat_request(request)
    response = post(config, protocol, body)
    try:
        if protocol == 'chat':
            message = response['choices'][0]['message']
            calls = [{'id': c['id'], 'name': c['function']['name'], 'arguments': c['function']['arguments']} for c in message.get('tool_calls', [])]
            text = text_content(message.get('content'))
            native = {'protocol': protocol, 'message': message}
        elif protocol == 'responses':
            output = response['output']
            calls = [{'id': c['call_id'], 'name': c['name'], 'arguments': c['arguments']} for c in output if c.get('type') == 'function_call']
            text = '\n'.join(text_content(c.get('content', [])) for c in output if c.get('type') == 'message')
            native = {'protocol': protocol, 'output': output}
        else:
            output = response['content']
            calls = [{'id': c['id'], 'name': c['name'], 'arguments': c['input']} for c in output if c.get('type') == 'tool_use']
            text = text_content(output)
            native = {'protocol': protocol, 'content': output}
        return {'text': text, 'toolCalls': normalized_calls(calls), 'native': native, 'usage': response.get('usage', {})}
    except (KeyError, IndexError, TypeError, AttributeError):
        raise APIError('Provider response does not match the selected protocol') from None


def search(request):
    config = request.get('config') or {}
    query = request.get('query', '')
    if not isinstance(query, str) or not query.strip() or len(query) > 1000:
        raise ValueError('Search query must contain 1–1000 characters')
    response = post(config, 'search', {'query': query, 'max_results': 6, 'search_depth': 'basic', 'include_images': bool(request.get('includeImages', True)), 'include_answer': False}, timeout=40)
    results = []
    for item in response.get('results', [])[:6]:
        if isinstance(item, dict):
            results.append({'title': str(item.get('title', ''))[:300], 'url': str(item.get('url', ''))[:2048], 'content': str(item.get('content', ''))[:5000], 'score': item.get('score')})
    images = []
    for value in response.get('images', [])[:12]:
        if isinstance(value, str):
            images.append({'url': value[:2048], 'description': ''})
        elif isinstance(value, dict):
            images.append({'url': str(value.get('url', ''))[:2048], 'description': str(value.get('description', ''))[:500]})
    return {'query': query, 'results': results, 'images': images}


def public_addresses(url):
    parsed = url_parts(url)
    if parsed.port not in (None, 80, 443):
        raise ValueError('Public image downloads only support ports 80 and 443')
    try:
        addresses = socket.getaddrinfo(parsed.hostname, parsed.port or (443 if parsed.scheme == 'https' else 80), type=socket.SOCK_STREAM)
    except OSError as exc:
        raise ValueError('Image host could not be resolved') from exc
    ips = list(dict.fromkeys(item[4][0] for item in addresses))
    if not ips or any(not ipaddress.ip_address(ip).is_global for ip in ips):
        raise ValueError('Image URL must resolve only to public internet addresses')
    return parsed, ips


def fetch_image(url):
    if not isinstance(url, str) or len(url) > 4096:
        raise ValueError('Invalid image URL')
    for _ in range(4):
        parsed, addresses = public_addresses(url)
        port = parsed.port or (443 if parsed.scheme == 'https' else 80)
        connection_type = http.client.HTTPSConnection if parsed.scheme == 'https' else http.client.HTTPConnection
        connection = connection_type(parsed.hostname, port=port, timeout=30)
        # Pin the checked address while preserving HTTPS hostname/SNI verification.
        connection._create_connection = lambda _address, timeout, source_address=None: socket.create_connection((addresses[0], port), timeout, source_address)
        try:
            connection.request('GET', urllib.parse.urlunsplit(('', '', parsed.path or '/', parsed.query, '')), headers={'Accept': 'image/*', 'User-Agent': 'Flue-Watchface-Prototype/1'})
            response = connection.getresponse()
            if response.status in (301, 302, 303, 307, 308):
                location = response.getheader('Location')
                if not location:
                    raise ValueError('Image download redirect has no target')
                url = urllib.parse.urljoin(url, location)
                continue
            if response.status != 200:
                raise APIError(f'Image download returned HTTP {response.status}')
            raw = response.read(image_tools.MAX_BYTES + 1)
            if len(raw) > image_tools.MAX_BYTES:
                raise ValueError('Downloaded image exceeds 10 MiB')
            return image_tools.result(image_tools.read_image(image_tools.data_url(raw, 'image/png')))
        except (OSError, TimeoutError, http.client.HTTPException) as exc:
            raise APIError('Image download failed: ' + str(exc)[:300]) from None
        finally:
            connection.close()
    raise ValueError('Too many image redirects')


def generate_image(request):
    config = request.get('config') or {}
    prompt = request.get('prompt', '')
    model = config.get('model', '')
    if not isinstance(prompt, str) or not prompt.strip() or len(prompt) > 16000:
        raise ValueError('Image prompt must contain 1–16000 characters')
    if not isinstance(model, str) or not model.strip() or len(model) > 200:
        raise ValueError('Set an image model name in API settings')
    fields = {'model': model, 'prompt': prompt, 'n': 1}
    if config.get('size'):
        fields['size'] = str(config['size'])[:40]
    if request.get('transparent'):
        fields['background'] = 'transparent'
    if config.get('responseFormat'):
        if config['responseFormat'] not in ('b64_json', 'url'):
            raise ValueError('Image responseFormat must be b64_json or url')
        fields['response_format'] = config['responseFormat']
    if request.get('image'):
        normalized = image_tools.result(image_tools.read_image(request['image']))['dataUrl']
        _, raw = image_tools.decode_data_url(normalized)
        boundary = 'FlueBoundary' + uuid.uuid4().hex
        parts = []
        for name, value in fields.items():
            parts.append(f'--{boundary}\r\nContent-Disposition: form-data; name="{name}"\r\n\r\n{value}\r\n'.encode('utf-8'))
        parts.append(f'--{boundary}\r\nContent-Disposition: form-data; name="image"; filename="input.png"\r\nContent-Type: image/png\r\n\r\n'.encode() + raw + b'\r\n')
        parts.append(f'--{boundary}--\r\n'.encode())
        response = post(config, 'image_edit', b''.join(parts), 'multipart/form-data; boundary=' + boundary, timeout=300)
    else:
        response = post(config, 'image', fields, timeout=300)
    try:
        item = response['data'][0]
        if item.get('b64_json'):
            result = image_tools.result(image_tools.read_image('data:image/png;base64,' + item['b64_json']))
        elif item.get('url'):
            result = fetch_image(item['url'])
        else:
            raise APIError('Image provider returned neither b64_json nor url')
        if item.get('revised_prompt'):
            result['revisedPrompt'] = str(item['revised_prompt'])[:16000]
        return result
    except (KeyError, IndexError, TypeError):
        raise APIError('Image provider returned an unexpected image response') from None
