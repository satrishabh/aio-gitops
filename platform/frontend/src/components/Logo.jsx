// AIO brand mark, vector-traced from the logo artwork. Letterforms use currentColor so
// they follow the theme; the accent dot keeps the brand blue.
const BLUE = '#1766F5'
const A = 'M127 1252 383 672h162l257 580H631l-46-112H340l-45 112zM388 1018h149l-74-186z'
const I = 'M840 672h164v580H840z'
const O = 'M1085 963a322 302 0 1 0 644 0a322 302 0 1 0-644 0zM1250 963a157 166 0 1 0 314 0a157 166 0 1 0-314 0z'
const Dot = () => <circle cx="1460" cy="963" r="114" fill={BLUE} />

// Compact mark (the "O" with its dot) for icon-sized slots.
export function LogoMark({ size = 24, className, style }) {
  return (
    <svg width={size} height={size} viewBox="1057 613 700 700" className={className} style={style}
         role="img" aria-label="AIO">
      <Dot />
      <path d={O} fill="currentColor" fillRule="evenodd" />
    </svg>
  )
}

// Full "AIO" wordmark.
export function Wordmark({ height = 20, className, style }) {
  return (
    <svg height={height} viewBox="100 640 1660 650" className={className} style={style} role="img" aria-label="AIO">
      <path d={A} fill="currentColor" fillRule="evenodd" />
      <path d={I} fill="currentColor" />
      <Dot />
      <path d={O} fill="currentColor" fillRule="evenodd" />
    </svg>
  )
}
