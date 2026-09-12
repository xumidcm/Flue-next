"""Loopback-only web prototype, with fixed tools and no general file/shell API."""
import argparse
import base64
import binascii
import io
import json
import mimetypes
import platform
import re
import stat
import threading
import time
import urllib.parse
import xml.etree.ElementTree as ET
import zipfile
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path, PurePosixPath

import numpy
import PIL
from PIL import Image

import image_tools
import providers

PUBLIC = Path(__file__).resolve().parent / 'public'
# Large enough to roundtrip every accepted 64 MiB expanded archive as base64 JSON.
MAX_BODY = 96 * 1024 * 1024
MAX_JSON_RESPONSE = 96 * 1024 * 1024
MAX_ZIP = 25 * 1024 * 1024
MAX_EXPANDED = 64 * 1024 * 1024
MAX_ENTRIES = 1024
MAX_XML = 2 * 1024 * 1024
MAX_SCRIPT = 2 * 1024 * 1024
SLOTS = threading.BoundedSemaphore(4)


def safe_name(name):
    if not isinstance(name, str) or not name or len(name) > 240 or '\\' in name or ':' in name or any(ord(c) < 32 for c in name):
        raise ValueError('Unsafe archive entry name')
    path = PurePosixPath(name)
    if path.is_absolute() or name.startswith('/') or any(part in ('', '.', '..') for part in name.split('/')):
        raise ValueError('Archive traversal and ambiguous names are not allowed')
    return name


def validate_xml(xml):
    if not isinstance(xml, str) or len(xml.encode('utf-8')) > MAX_XML:
        raise ValueError('Watch XML must be text smaller than 2 MiB')
    if re.search(r'<!\s*(?:DOCTYPE|ENTITY)\b', xml, re.IGNORECASE):
        raise ValueError('DOCTYPE and XML entities are not allowed')
    try:
        root = ET.fromstring(xml)
    except ET.ParseError as exc:
        raise ValueError('Invalid watch XML: ' + str(exc)[:200]) from None
    if root.tag != 'Watch':
        raise ValueError('Watch XML must have a <Watch> root element')
    nodes = list(root.iter())
    if len(nodes) > 8192 or sum(node.tag.lower() == 'layer' for node in nodes) > 512:
        raise ValueError('Watch XML exceeds the 512 layer / 8192 element limit')
    stack = [(root, 1)]
    while stack:
        element, depth = stack.pop()
        if depth > 64:
            raise ValueError('Watch XML nesting exceeds 64 levels')
        stack.extend((child, depth + 1) for child in element)
    return root


def decode_zip_data(value):
    if not isinstance(value, str) or len(value) > MAX_ZIP * 4 // 3 + 1024:
        raise ValueError('Watch archive exceeds 25 MiB')
    try:
        raw = base64.b64decode(value, validate=True)
    except (ValueError, binascii.Error):
        raise ValueError('Invalid archive base64') from None
    if len(raw) > MAX_ZIP:
        raise ValueError('Watch archive exceeds 25 MiB')
    return raw


def import_watch(request):
    raw = decode_zip_data(request.get('data'))
    warnings = []
    try:
        with zipfile.ZipFile(io.BytesIO(raw)) as archive:
            infos = archive.infolist()
            if len(infos) > MAX_ENTRIES or sum(info.file_size for info in infos) > MAX_EXPANDED:
                raise ValueError('Watch archive exceeds entry count or 64 MiB expanded limit')
            entries, seen = {}, set()
            for info in infos:
                original = info.orig_filename
                name = safe_name(original[:-1] if info.is_dir() and original.endswith('/') else original)
                if name.casefold() in seen:
                    raise ValueError('Duplicate archive entry names are not allowed')
                seen.add(name.casefold())
                if stat.S_ISLNK(info.external_attr >> 16) or info.flag_bits & 1:
                    raise ValueError('Symbolic links and encrypted archive entries are not supported')
                if info.is_dir():
                    continue
                if info.file_size > image_tools.MAX_BYTES:
                    raise ValueError('Each archive asset must be at most 10 MiB')
                # read() verifies CRC; names are never used for filesystem extraction.
                entries[name] = archive.read(info)
    except (zipfile.BadZipFile, RuntimeError, NotImplementedError, EOFError, OSError) as exc:
        raise ValueError('Invalid or unsupported watch ZIP archive') from exc
    xml_name = next((name for name in entries if name.casefold() == 'watch.xml'), None)
    if not xml_name:
        raise ValueError('Watch archive must contain watch.xml at its root')
    try:
        xml = entries.pop(xml_name).decode('utf-8-sig')
    except UnicodeDecodeError:
        raise ValueError('watch.xml must use UTF-8 encoding') from None
    validate_xml(xml)
    script = ''
    # Only the canonical WatchMaker entry is the global script. Other Lua assets
    # may be dependencies and must keep their original paths and bytes on export.
    script_name = 'scripts/script.txt' if 'scripts/script.txt' in entries else None
    if script_name:
        raw_script = entries.pop(script_name)
        if len(raw_script) > MAX_SCRIPT:
            raise ValueError('Main Lua script exceeds 2 MiB')
        try:
            script = raw_script.decode('utf-8-sig')
        except UnicodeDecodeError:
            raise ValueError('Main Lua script must use UTF-8 encoding') from None
    assets = []
    for name, content in entries.items():
        mime = mimetypes.guess_type(name)[0] or 'application/octet-stream'
        assets.append({'name': name, 'dataUrl': image_tools.data_url(content, mime)})
        if not name.startswith(('images/', 'fonts/')) and name not in ('preview.png', 'preview.jpg'):
            warnings.append('Preserved additional archive asset: ' + name)
    if script:
        warnings.append('Lua is preserved for export; this browser prototype does not execute it.')
    return {'xml': xml, 'script': script, 'assets': assets, 'warnings': warnings[:40]}


def export_watch(request):
    xml = request.get('xml')
    validate_xml(xml)
    script = request.get('script') or ''
    if not isinstance(script, str) or len(script.encode('utf-8')) > MAX_SCRIPT:
        raise ValueError('Main Lua script must be text smaller than 2 MiB')
    assets = request.get('assets', [])
    if not isinstance(assets, list) or len(assets) > MAX_ENTRIES - 3:
        raise ValueError('Too many watch archive assets')
    entries = {'watch.xml': xml.encode('utf-8')}
    if script:
        entries['scripts/script.txt'] = script.encode('utf-8')
    seen = {name.casefold() for name in entries}
    for asset in assets:
        if not isinstance(asset, dict):
            raise ValueError('Invalid archive asset')
        name = safe_name(asset.get('name'))
        if name.casefold() in seen or name.casefold() == 'watch.xml':
            raise ValueError('Duplicate or reserved archive asset name: ' + name)
        seen.add(name.casefold())
        _, content = image_tools.decode_data_url(asset.get('dataUrl'))
        entries[name] = content
    if request.get('preview'):
        preview = image_tools.read_image(request['preview'])
        backdrop = Image.new('RGB', preview.size, '#000000')
        backdrop.paste(preview, mask=preview.getchannel('A'))
        preview_buffer = io.BytesIO()
        backdrop.save(preview_buffer, format='JPEG', quality=92)
        preview_bytes = preview_buffer.getvalue()
        # Native Flue catalogs read preview.jpg, with preview_dim.jpg as a fallback.
        existing = next((name for name in entries if name.casefold() == 'preview.jpg'), None)
        if existing:
            entries[existing] = preview_bytes
        else:
            entries['preview.jpg'] = preview_bytes
        dim_name = next((name for name in entries if name.casefold() == 'preview_dim.jpg'), None)
        if dim_name:
            entries[dim_name] = preview_bytes
    if sum(len(content) for content in entries.values()) > MAX_EXPANDED:
        raise ValueError('Watch export exceeds 64 MiB expanded limit')
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, 'w', compression=zipfile.ZIP_DEFLATED, compresslevel=6) as archive:
        for name, content in entries.items():
            archive.writestr(name, content)
    if buf.tell() > MAX_ZIP:
        raise ValueError('Compressed watch export exceeds 25 MiB')
    return {'data': base64.b64encode(buf.getvalue()).decode('ascii'), 'filename': 'flue-agent.watch'}


def runtime():
    return {'python': platform.python_version(), 'pillow': PIL.__version__, 'numpy': numpy.__version__, 'operations': image_tools.OPERATIONS, 'generalPython': False, 'shell': False, 'limits': {'imageBytes': image_tools.MAX_BYTES, 'imageDimension': image_tools.MAX_DIMENSION, 'archiveBytes': MAX_ZIP, 'archiveExpandedBytes': MAX_EXPANDED, 'layers': 512}, 'notes': ['remove_background uses color distance and threshold; it is not semantic segmentation.']}


def test_connection(request):
    start = time.perf_counter()
    config = dict(request.get('config') or request)
    config['maxTokens'] = 128
    output = providers.chat({'config': config, 'system': '', 'messages': [{'role': 'user', 'content': 'Reply with OK only.'}]})
    return {'ok': True, 'text': output['text'][:1000], 'latencyMs': round((time.perf_counter() - start) * 1000), 'usage': output['usage']}


POST_ROUTES = {'/api/chat': providers.chat, '/api/test': test_connection, '/api/search': providers.search, '/api/image': providers.generate_image, '/api/image-process': image_tools.process, '/api/import-watch': import_watch, '/api/export-watch': export_watch, '/api/fetch-image': lambda request: providers.fetch_image(request.get('url'))}


class Handler(BaseHTTPRequestHandler):
    server_version = 'FlueWatchface/0.1'
    sys_version = ''

    def log_message(self, *_args):
        # Request bodies, keys, and model replies must never enter server logs.
        pass

    def setup(self):
        super().setup()
        self.connection.settimeout(30)

    def end_headers(self):
        self.send_header('X-Content-Type-Options', 'nosniff')
        self.send_header('Referrer-Policy', 'no-referrer')
        self.send_header('Cache-Control', 'no-store')
        self.send_header('Content-Security-Policy', "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data: blob:; font-src 'self' data: blob:; connect-src 'self'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'")
        super().end_headers()

    def send_json(self, status, payload):
        raw = json.dumps(payload, ensure_ascii=False, allow_nan=False).encode('utf-8')
        if len(raw) > MAX_JSON_RESPONSE:
            status, raw = 413, b'{"error":"Response is too large"}'
        try:
            self.send_response(status)
            self.send_header('Content-Type', 'application/json; charset=utf-8')
            self.send_header('Content-Length', str(len(raw)))
            self.end_headers()
            self.wfile.write(raw)
        except (BrokenPipeError, ConnectionResetError, TimeoutError):
            # Browser cancellation stops waiting, but cannot cancel a submitted billable request.
            pass

    def check_origin(self):
        port = self.server.server_port
        allowed = {f'127.0.0.1:{port}', f'localhost:{port}'}
        if port == 80:
            allowed.update(('127.0.0.1', 'localhost'))
        host = self.headers.get('Host', '').lower()
        if len(self.headers.get_all('Host', [])) != 1 or host not in allowed:
            self.send_json(403, {'error': 'Only the local application host is allowed'})
            return False
        origin = self.headers.get('Origin')
        if (origin and origin.lower() != 'http://' + host) or self.headers.get('Sec-Fetch-Site') == 'cross-site':
            self.send_json(403, {'error': 'Cross-origin requests are not allowed'})
            return False
        return True

    def do_GET(self):
        if not self.check_origin():
            return
        path = urllib.parse.urlsplit(self.path).path
        if path == '/api/runtime':
            self.send_json(200, runtime())
            return
        if path.startswith('/api/'):
            self.send_json(404, {'error': 'Unknown endpoint'})
            return
        try:
            decoded = urllib.parse.unquote(path, errors='strict')
            candidate = (PUBLIC / (decoded.lstrip('/') or 'index.html')).resolve()
            if not candidate.is_relative_to(PUBLIC.resolve()) or not candidate.is_file() or candidate.stat().st_size > 10 * 1024 * 1024:
                self.send_json(404, {'error': 'File not found'})
                return
            content = candidate.read_bytes()
            mime = {'.js': 'text/javascript', '.css': 'text/css', '.html': 'text/html'}.get(candidate.suffix, mimetypes.guess_type(candidate.name)[0] or 'application/octet-stream')
            self.send_response(200)
            self.send_header('Content-Type', mime + ('; charset=utf-8' if mime.startswith('text/') else ''))
            self.send_header('Content-Length', str(len(content)))
            self.end_headers()
            self.wfile.write(content)
        except (OSError, ValueError, UnicodeError):
            self.send_json(404, {'error': 'File not found'})

    def do_POST(self):
        if not self.check_origin():
            return
        route = POST_ROUTES.get(urllib.parse.urlsplit(self.path).path)
        if not route:
            self.send_json(404, {'error': 'Unknown endpoint'})
            return
        if self.headers.get('Content-Type', '').split(';')[0].strip().lower() != 'application/json':
            self.send_json(415, {'error': 'Content-Type must be application/json'})
            return
        try:
            if self.headers.get('Transfer-Encoding') or len(self.headers.get_all('Content-Length', [])) != 1:
                raise ValueError('A single Content-Length is required')
            length = int(self.headers.get('Content-Length', '-1'))
            if not 0 < length <= MAX_BODY:
                self.send_json(413, {'error': 'Request exceeds the 96 MiB body limit'})
                return
        except ValueError as exc:
            self.send_json(400, {'error': str(exc)})
            return
        if not SLOTS.acquire(blocking=False):
            self.send_json(503, {'error': 'Local backend is busy; wait for the active requests to finish'})
            return
        request = {}
        try:
            raw = self.rfile.read(length)
            if len(raw) != length:
                raise ValueError('Incomplete request body')
            parsed_request = json.loads(raw, parse_constant=lambda _value: (_ for _ in ()).throw(ValueError('Non-finite JSON numbers are not allowed')))
            if not isinstance(parsed_request, dict):
                raise ValueError('Request body must be a JSON object')
            request = parsed_request
            if 'config' in request:
                if not isinstance(request['config'], dict):
                    raise ValueError('API config must be a JSON object')
                if 'headers' in request['config'] and not isinstance(request['config']['headers'], dict):
                    raise ValueError('Extra headers must be a JSON object')
            self.send_json(200, route(request))
        except providers.APIError as exc:
            self.send_json(exc.status, {'error': providers.redact(str(exc), request.get('config') or {})})
        except (ValueError, TypeError, KeyError, OverflowError, UnicodeError) as exc:
            self.send_json(400, {'error': providers.redact(str(exc), request.get('config') or {})})
        except TimeoutError:
            self.send_json(408, {'error': 'Request timed out'})
        except Exception:
            # Do not leak request payloads, local paths, or keys through tracebacks.
            self.send_json(500, {'error': 'Local backend could not process this request'})
        finally:
            SLOTS.release()

    def do_OPTIONS(self):
        if self.check_origin():
            self.send_json(405, {'error': 'Cross-origin API access is not supported'})


def create_server(port=8765):
    server = ThreadingHTTPServer(('127.0.0.1', port), Handler)
    server.daemon_threads = True
    return server


def main():
    parser = argparse.ArgumentParser(description='Run the local Flue watchface Agent prototype')
    parser.add_argument('--port', type=int, default=8765)
    args = parser.parse_args()
    if not 1 <= args.port <= 65535:
        parser.error('--port must be between 1 and 65535')
    server = create_server(args.port)
    print(f'Flue watchface editor: http://127.0.0.1:{server.server_port}', flush=True)
    print('Ctrl+C stops the server. API keys are never saved by the backend.', flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == '__main__':
    main()
