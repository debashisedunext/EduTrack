/**
 * The one place a product code becomes a glyph.
 *
 * The mockup draws every journey strip and product chip with an icon
 * (`prodById(j2.prod).icon`), but `ObProductRef` is deliberately three fields
 * — id, code, name — and growing the contract for a decoration would put an
 * emoji column in a dozen generated types. So the glyph is presentation,
 * keyed on the stable `code` the product master already guarantees, with a
 * neutral default for any code this map has never heard of. Wrong-but-generic
 * beats a contract change for a picture.
 */
const ICONS: Record<string, string> = {
  ERP: '🏫',
  BIOMETRIC: '🪪',
  LMS: '📚',
  APP: '📱',
}

export function productIcon(code: string | undefined): string {
  return (code && ICONS[code]) || '📦'
}
