import * as React from 'react'

/**
 * A-055 · the trend line on a KPI card.
 *
 * <p>Inline SVG rather than a charting library. A sparkline is a polyline with
 * no axes, no legend and no interaction; pulling in Recharts for six of them
 * would add a bundle for something `<path>` already does, and every chart
 * library's default is a tooltip nobody asked for on a 60-pixel graphic.
 */
export interface SparklineProps {
  /** Oldest first. Fewer than two points draws nothing — a line needs somewhere to go. */
  points: number[]
  /** Stroke colour, a design token. Never a literal — blueprint §12.1. */
  stroke?: string
  className?: string
  /** Announced to screen readers in place of the drawing. */
  label: string
}

const WIDTH = 96
const HEIGHT = 28
const PADDING = 2

export function Sparkline({ points, stroke = 'var(--primary)', className, label }: SparklineProps) {
  // One point is a dot, not a trend, and zero points is a card with no history
  // yet. Both render as nothing rather than as a flat line, because a flat line
  // is a claim that the value did not move.
  if (points.length < 2) {
    return null
  }

  const min = Math.min(...points)
  const max = Math.max(...points)
  const span = max - min

  const stepX = (WIDTH - PADDING * 2) / (points.length - 1)

  // A genuinely flat series has span 0 and would divide by zero. Drawn along
  // the middle instead of the bottom: a week of "12 open" every day is steady,
  // not empty, and pinning it to the axis reads as a collapse.
  const y = (value: number) =>
    span === 0
      ? HEIGHT / 2
      : HEIGHT - PADDING - ((value - min) / span) * (HEIGHT - PADDING * 2)

  const d = points
    .map((value, i) => `${i === 0 ? 'M' : 'L'} ${(PADDING + i * stepX).toFixed(2)} ${y(value).toFixed(2)}`)
    .join(' ')

  return (
    <svg
      className={className}
      width={WIDTH}
      height={HEIGHT}
      viewBox={`0 0 ${WIDTH} ${HEIGHT}`}
      role="img"
      aria-label={label}
      focusable="false"
    >
      <path
        d={d}
        fill="none"
        stroke={stroke}
        strokeWidth="1.5"
        strokeLinecap="round"
        strokeLinejoin="round"
        vectorEffect="non-scaling-stroke"
      />
    </svg>
  )
}
