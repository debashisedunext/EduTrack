import { http, HttpResponse } from 'msw';

/**
 * `/mock-files/*` — the object store, such as it is. C-026.
 *
 * ## Why this file had to exist
 *
 * `attachmentDto` has been minting `/mock-files/{id}/{name}` download URLs and
 * `/mock-files/{id}/thumb.png` thumbnail URLs since C-023, and **nothing has ever
 * served that path.** It did not matter while no screen rendered them; C-023's
 * own note predicted the day it would ("C-026's gallery will 404 on every
 * image"), and this is that day. Without a handler the whole strip renders broken
 * images under `npm run dev` while working perfectly against the real backend,
 * which is the worst possible way round — it teaches everyone to distrust the
 * feature rather than the fixture.
 *
 * Not under `/api/v1`, so the catch-all 501 in `index.ts` never covered it. A
 * signed URL in production points at MinIO, not at the API, and the mock is
 * faithful to that.
 *
 * ## Real PNG bytes, generated here
 *
 * A solid colour derived from the path, so every attachment is a different
 * swatch and the lightbox's next/previous is visibly doing something. Three
 * alternatives were considered and rejected:
 *
 * - **A committed fixture image.** One PNG means every tile looks identical,
 *   which makes navigation and ordering bugs invisible.
 * - **SVG.** Much less code and renders fine in an `<img>` — but the real server
 *   serves `image/png` from `ThumbnailGenerator`, and a mock that serves a
 *   different media type is a difference waiting to hide something.
 * - **A canvas.** Not available under Vitest, where these handlers also run.
 *
 * So the encoder below is real, if minimal: a stored (uncompressed) DEFLATE
 * stream inside a valid zlib wrapper. Uncompressed because a solid colour
 * compresses to nothing anyway and pulling in a deflate implementation for a
 * fixture is not a trade worth making.
 */

const PNG_SIGNATURE = [0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a];

/** Table-free CRC32 — a few hundred bytes per image, so the table is not worth it. */
function crc32(bytes: Uint8Array): number {
  let crc = 0xffffffff;
  for (const byte of bytes) {
    crc ^= byte;
    for (let bit = 0; bit < 8; bit += 1) {
      crc = crc & 1 ? (crc >>> 1) ^ 0xedb88320 : crc >>> 1;
    }
  }
  return (crc ^ 0xffffffff) >>> 0;
}

/** zlib's checksum, which is not the same as the chunk CRC and is easy to conflate. */
function adler32(bytes: Uint8Array): number {
  let a = 1;
  let b = 0;
  for (const byte of bytes) {
    a = (a + byte) % 65521;
    b = (b + a) % 65521;
  }
  return ((b << 16) | a) >>> 0;
}

function be32(value: number): number[] {
  return [(value >>> 24) & 0xff, (value >>> 16) & 0xff, (value >>> 8) & 0xff, value & 0xff];
}

function chunk(type: string, data: number[]): number[] {
  const typeBytes = [...type].map((character) => character.charCodeAt(0));
  const body = Uint8Array.from([...typeBytes, ...data]);
  // The CRC covers the type and the data, but not the length — a classic
  // off-by-one-field that produces a file every decoder rejects.
  return [...be32(data.length), ...body, ...be32(crc32(body))];
}

/** A `width` × `height` PNG filled with one colour. */
function solidPng(width: number, height: number, rgb: [number, number, number]): Uint8Array {
  const raw: number[] = [];
  for (let y = 0; y < height; y += 1) {
    raw.push(0); // per-scanline filter type: none
    for (let x = 0; x < width; x += 1) raw.push(...rgb);
  }
  const rawBytes = Uint8Array.from(raw);

  // zlib header for "deflate, 32K window, default compression", then stored
  // blocks of at most 65535 bytes each, then the Adler-32 of the raw data.
  const zlib: number[] = [0x78, 0x01];
  for (let offset = 0; offset < rawBytes.length; offset += 0xffff) {
    const block = rawBytes.subarray(offset, offset + 0xffff);
    const isLast = offset + 0xffff >= rawBytes.length;
    zlib.push(isLast ? 1 : 0);
    // LEN then its one's complement, both little-endian. A decoder checks them
    // against each other, so a wrong NLEN fails as "invalid stored block".
    zlib.push(block.length & 0xff, (block.length >>> 8) & 0xff);
    zlib.push(~block.length & 0xff, (~block.length >>> 8) & 0xff);
    zlib.push(...block);
  }
  zlib.push(...be32(adler32(rawBytes)));

  return Uint8Array.from([
    ...PNG_SIGNATURE,
    // 8-bit colour depth, colour type 2 (truecolour), no interlacing.
    ...chunk('IHDR', [...be32(width), ...be32(height), 8, 2, 0, 0, 0]),
    ...chunk('IDAT', zlib),
    ...chunk('IEND', []),
  ]);
}

/** Stable per path, so a reload does not reshuffle every tile on the ticket. */
function colourFor(path: string): [number, number, number] {
  let hash = 0;
  for (let i = 0; i < path.length; i += 1) hash = (hash * 31 + path.charCodeAt(i)) >>> 0;
  // Kept mid-range: a white swatch is invisible against the surface and a black
  // one hides the lightbox's own controls.
  return [80 + (hash % 140), 80 + ((hash >>> 8) % 140), 80 + ((hash >>> 16) % 140)];
}

export const fileHandlers = [
  http.get('/mock-files/*', ({ request }) => {
    const path = new URL(request.url).pathname;

    // A thumbnail is small; anything else stands in for the full-size file and
    // is deliberately larger, so the gallery visibly loads the reduction and the
    // lightbox visibly loads the original.
    const isThumbnail = path.endsWith('/thumb.png');
    const size = isThumbnail ? 320 : 1200;

    const body = solidPng(size, isThumbnail ? size : 800, colourFor(path));
    return HttpResponse.arrayBuffer(body.buffer as ArrayBuffer, {
      headers: {
        'Content-Type': 'image/png',
        // What the real presigner sets. Harmless for an <img>, which renders
        // regardless — and worth mirroring so nobody discovers the header for
        // the first time in production.
        'Content-Disposition': 'attachment',
        'Cache-Control': 'no-store',
      },
    });
  }),
];
