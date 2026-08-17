import { ResponsiveContainer, Treemap } from 'recharts'

import { ChartCanvas, type WidgetSeries } from '../WidgetFrame'
import { ChartLegend } from './ChartLegend'
import { categorical } from './chartTokens'
import { useDrillDown } from './useDrillDown'

/**
 * A-057 · widget 15 — open tickets per project, sized by share.
 *
 * <h2>Labels are drawn only where they fit</h2>
 *
 * A treemap's small rectangles cannot hold their own names, and recharts will
 * happily paint text straight over the edges of a 12-pixel tile and into its
 * neighbour. So the custom content below measures the rectangle first and draws
 * the label only when there is room — every project remains reachable through
 * the legend and the hidden data table regardless, which is the same division of
 * labour the donut uses.
 *
 * <h2>Why a legend on a treemap at all</h2>
 *
 * The tiles are labelled, so a legend looks redundant. It is not: the drawing is
 * `aria-hidden` and the tiles are `<path>` elements that cannot take focus, so
 * without the legend the deep-links would be mouse-only — and §S-05 requires
 * every chart segment to deep-link, not every chart segment a mouse can reach.
 */

interface TileProps {
  x?: number
  y?: number
  width?: number
  height?: number
  index?: number
  name?: string
  value?: number
  drillDown?: string | null
  onSelect?: (drillDown: string | null) => void
}

function Tile({ x = 0, y = 0, width = 0, height = 0, index = 0, name, value, drillDown, onSelect }: TileProps) {
  // Enough room for the name and the figure beneath it, with margin. Below
  // this the tile is drawn bare rather than mislabelled.
  const roomForLabel = width > 64 && height > 34

  return (
    <g
      style={{ cursor: drillDown ? 'pointer' : 'default' }}
      onClick={() => onSelect?.(drillDown ?? null)}
    >
      <rect
        x={x}
        y={y}
        width={width}
        height={height}
        fill={categorical(index)}
        stroke="var(--bg-surface)"
        strokeWidth={2}
      />
      {roomForLabel && (
        <>
          <text x={x + 6} y={y + 16} fontSize={11} fill="#FFFFFF" fontWeight={600}>
            {name}
          </text>
          <text x={x + 6} y={y + 29} fontSize={11} fill="#FFFFFF" fillOpacity={0.85}>
            {value}
          </text>
        </>
      )}
      <title>{`${name}: ${value}`}</title>
    </g>
  )
}

export function ProjectTreemap({ series }: { series: WidgetSeries[] }) {
  const drillDown = useDrillDown('Project distribution')
  const points = series[0]?.points ?? []

  const data = points.map((point) => ({
    name: point.x,
    size: point.y,
    value: point.y,
    drillDown: point.drillDown,
  }))

  return (
    <>
      <ChartCanvas>
        <ResponsiveContainer width="100%" height="100%">
          <Treemap
            data={data}
            dataKey="size"
            isAnimationActive={false}
            stroke="var(--bg-surface)"
            content={<Tile onSelect={drillDown} />}
          />
        </ResponsiveContainer>
      </ChartCanvas>

      <ChartLegend
        label="Projects, with the number of open tickets in each"
        entries={points.map((point, index) => ({
          label: point.x,
          colour: categorical(index),
          drillDown: point.drillDown,
          value: point.y,
        }))}
        onSelect={drillDown}
      />
    </>
  )
}
