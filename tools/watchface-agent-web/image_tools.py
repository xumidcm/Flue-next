"""Fixed, bounded image operations. This module never evaluates user code."""
import base64
import binascii
import io
import math
import re
import warnings

import numpy as np
from PIL import Image, ImageColor, ImageDraw, ImageEnhance, ImageOps

MAX_BYTES = 10 * 1024 * 1024
MAX_DIMENSION = 4096
MAX_PIXELS = 4096 * 4096
Image.MAX_IMAGE_PIXELS = MAX_PIXELS
OPERATIONS = ['gradient', 'ticks', 'resize', 'crop', 'rotate', 'adjust', 'remove_background']


def decode_data_url(value, max_bytes=MAX_BYTES):
    if not isinstance(value, str) or len(value) > max_bytes * 4 // 3 + 2048:
        raise ValueError('Asset data is missing or too large')
    match = re.fullmatch(r'data:([\w.+/-]+);base64,([A-Za-z0-9+/=\r\n]*)', value)
    if not match:
        raise ValueError('Expected a base64 data URL')
    try:
        raw = base64.b64decode(match[2].replace('\r', '').replace('\n', ''), validate=True)
    except (ValueError, binascii.Error) as exc:
        raise ValueError('Invalid base64 image or asset') from exc
    if len(raw) > max_bytes:
        raise ValueError('Asset exceeds 10 MiB limit')
    return match[1].lower(), raw


def data_url(raw, mime='application/octet-stream'):
    return 'data:' + mime + ';base64,' + base64.b64encode(raw).decode('ascii')


def dimensions(width, height):
    try:
        width, height = int(width), int(height)
    except (ValueError, TypeError, OverflowError) as exc:
        raise ValueError('Width and height must be integers') from exc
    if not 1 <= width <= MAX_DIMENSION or not 1 <= height <= MAX_DIMENSION or width * height > MAX_PIXELS:
        raise ValueError('Images must be 1–4096 pixels per side and at most 16 megapixels')
    return width, height


def read_image(value):
    mime, raw = decode_data_url(value)
    if not mime.startswith('image/'):
        raise ValueError('Expected image data')
    try:
        with warnings.catch_warnings():
            warnings.simplefilter('error', Image.DecompressionBombWarning)
            with Image.open(io.BytesIO(raw)) as opened:
                if opened.format not in ('PNG', 'JPEG', 'WEBP', 'GIF', 'BMP', 'TIFF'):
                    raise ValueError('Unsupported raster image format')
                dimensions(*opened.size)
                return ImageOps.exif_transpose(opened).convert('RGBA')
    except (OSError, Image.DecompressionBombError, Image.DecompressionBombWarning) as exc:
        raise ValueError('Invalid image or image dimensions exceed the limit') from exc


def result(image):
    dimensions(*image.size)
    buf = io.BytesIO()
    image.save(buf, format='PNG')
    if buf.tell() > MAX_BYTES:
        raise ValueError('Processed PNG exceeds 10 MiB limit; use a smaller canvas')
    return {'dataUrl': data_url(buf.getvalue(), 'image/png'), 'width': image.width, 'height': image.height}


def number(args, key, default, low, high):
    try:
        value = float(args.get(key, default))
    except (TypeError, ValueError, OverflowError) as exc:
        raise ValueError(key + ' must be a number') from exc
    if not math.isfinite(value) or not low <= value <= high:
        raise ValueError(f'{key} must be between {low} and {high}')
    return value


def color(value):
    if not isinstance(value, str) or len(value) > 50:
        raise ValueError('Invalid color')
    try:
        return ImageColor.getcolor(value, 'RGBA')
    except (ValueError, TypeError) as exc:
        raise ValueError('Invalid color: use #RRGGBB or #RRGGBBAA') from exc


def process(args):
    op = args.get('operation')
    if op not in OPERATIONS:
        raise ValueError('Unsupported image operation')
    if op in ('gradient', 'ticks'):
        width, height = dimensions(args.get('width', 512), args.get('height', 512))
        if op == 'gradient':
            colors = args.get('colors', ['#18233b', '#6650a0'])
            if not isinstance(colors, list) or len(colors) != 2:
                raise ValueError('Gradient needs exactly two colors')
            first, last = color(colors[0]), color(colors[1])
            direction = args.get('direction', 'vertical')
            if direction == 'radial':
                yy, xx = np.ogrid[-1:1:complex(height), -1:1:complex(width)]
                fraction = np.minimum(1, np.sqrt(xx * xx + yy * yy)).astype(np.float32)
            elif direction == 'horizontal':
                fraction = np.broadcast_to(np.linspace(0, 1, width, dtype=np.float32)[None, :], (height, width))
            elif direction == 'vertical':
                fraction = np.broadcast_to(np.linspace(0, 1, height, dtype=np.float32)[:, None], (height, width))
            else:
                raise ValueError('Gradient direction must be vertical, horizontal or radial')
            pixels = np.empty((height, width, 4), dtype=np.uint8)
            for channel in range(4):
                pixels[:, :, channel] = np.rint(first[channel] + fraction * (last[channel] - first[channel])).astype(np.uint8)
            return result(Image.fromarray(pixels))
        image = Image.new('RGBA', (width, height))
        draw = ImageDraw.Draw(image)
        count = int(number(args, 'count', 60, 1, 720))
        major_every = int(number(args, 'majorEvery', 5, 1, 720))
        radius = number(args, 'radius', min(width, height) * .46, 1, min(width, height) / 2)
        major = number(args, 'majorLength', radius * .12, 0, radius)
        minor = number(args, 'minorLength', radius * .055, 0, radius)
        stroke = int(number(args, 'stroke', max(1, width / 256), 1, 128))
        ink = color(args.get('color', '#ffffff'))
        for index in range(count):
            angle = 2 * math.pi * index / count - math.pi / 2
            inner = radius - (major if index % major_every == 0 else minor)
            points = [(width / 2 + math.cos(angle) * distance, height / 2 + math.sin(angle) * distance) for distance in (inner, radius)]
            draw.line(points, fill=ink, width=stroke)
        return result(image)

    image = read_image(args.get('image'))
    if op == 'resize':
        size = dimensions(args.get('width', image.width), args.get('height', image.height))
        image = image.resize(size, Image.Resampling.LANCZOS)
    elif op == 'crop':
        box = args.get('box')
        if not isinstance(box, list) or len(box) != 4:
            raise ValueError('Crop box must be [left, top, right, bottom]')
        try:
            left, top, right, bottom = (int(v) for v in box)
        except (ValueError, TypeError, OverflowError) as exc:
            raise ValueError('Invalid crop box') from exc
        if not 0 <= left < right <= image.width or not 0 <= top < bottom <= image.height:
            raise ValueError('Crop box must lie inside the image')
        image = image.crop((left, top, right, bottom))
    elif op == 'rotate':
        angle = number(args, 'degrees', 0, -36000, 36000)
        # Bound expanded allocation before Pillow creates its output buffer.
        if args.get('expand', True):
            radians = math.radians(angle)
            dimensions(math.ceil(abs(image.width * math.cos(radians)) + abs(image.height * math.sin(radians))), math.ceil(abs(image.width * math.sin(radians)) + abs(image.height * math.cos(radians))))
        image = image.rotate(-angle, resample=Image.Resampling.BICUBIC, expand=bool(args.get('expand', True)))
    elif op == 'adjust':
        alpha = image.getchannel('A')
        for key, enhancer in [('brightness', ImageEnhance.Brightness), ('contrast', ImageEnhance.Contrast), ('saturation', ImageEnhance.Color)]:
            image = enhancer(image).enhance(number(args, key, 1, 0, 5))
        image.putalpha(alpha)
    elif op == 'remove_background':
        target = color(args.get('color', '#ffffff'))
        threshold = number(args, 'threshold', 35, 0, 442)
        feather = number(args, 'feather', 12, 0, 255)
        pixels = np.array(image)
        distance = np.zeros((image.height, image.width), dtype=np.float32)
        for channel in range(3):
            distance += (pixels[:, :, channel].astype(np.float32) - target[channel]) ** 2
        np.sqrt(distance, out=distance)
        mask = np.clip((distance - threshold) / feather, 0, 1) if feather else (distance > threshold)
        pixels[:, :, 3] = np.rint(pixels[:, :, 3] * mask).astype(np.uint8)
        image = Image.fromarray(pixels)
    return result(image)
