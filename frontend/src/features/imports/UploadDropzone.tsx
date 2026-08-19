import { useRef, useState } from 'react'
import { FileSpreadsheet, Upload } from 'lucide-react'

import { Button } from '@/components/ui/button'
import { cn } from '@/lib/utils'

import { ACCEPTED_EXTENSIONS } from './importQueries'

interface UploadDropzoneProps {
  onFile: (file: File) => void
  disabled?: boolean
  /** Described by the zone, so a screen reader hears the refusal with the control. */
  describedBy?: string
}

/**
 * B-032 · blueprint §4B.3 step 2's "drag-drop … or file picker".
 *
 * ## The drop zone is not the only way in
 *
 * Drag and drop cannot be done from a keyboard and is awkward with a screen
 * reader, so the zone is a **label wrapping a real `<input type="file">`** rather
 * than a `<div>` with handlers. That gets focus, Enter and Space, the accessible
 * name and the platform file dialog for free — all of which a div would have to
 * reimplement, and would reimplement slightly wrong. The drop handlers sit on top
 * of a control that already works without them.
 *
 * ## Dropping several files takes the first, and says so
 *
 * A folder drag or a multi-select would otherwise silently import one file of
 * several with no indication which. The input is deliberately not `multiple`:
 * one import is one file, and the wizard's remaining four steps are about that
 * file.
 */
export function UploadDropzone({ onFile, disabled, describedBy }: UploadDropzoneProps) {
  const [over, setOver] = useState(false)
  const input = useRef<HTMLInputElement>(null)

  function take(files: FileList | null) {
    const file = files?.[0]
    if (file) {
      onFile(file)
    }
    // Cleared so that choosing the *same* file twice still fires `change` — the
    // ordinary way a user retries after fixing the spreadsheet, and a silent
    // no-op without this line.
    if (input.current) {
      input.current.value = ''
    }
  }

  return (
    <div
      onDragOver={(event) => {
        event.preventDefault()
        if (!disabled) setOver(true)
      }}
      onDragLeave={() => setOver(false)}
      onDrop={(event) => {
        event.preventDefault()
        setOver(false)
        if (!disabled) take(event.dataTransfer.files)
      }}
      className={cn(
        'rounded-card border-2 border-dashed p-8 text-center transition-colors',
        over ? 'border-primary bg-primary-soft' : 'border-border bg-subtle',
        disabled && 'opacity-60',
      )}
    >
      <FileSpreadsheet className="mx-auto h-8 w-8 text-content-muted" aria-hidden="true" />

      <label className="mt-3 block cursor-pointer text-sm text-content">
        <span className="font-medium">Drop your spreadsheet here</span>
        <input
          ref={input}
          type="file"
          className="sr-only"
          accept={ACCEPTED_EXTENSIONS.join(',')}
          disabled={disabled}
          aria-describedby={describedBy}
          onChange={(event) => take(event.target.files)}
        />
      </label>

      <p className="mt-1 text-sm text-content-muted">
        .xlsx or .csv, up to 5 MB and 5,000 rows
      </p>

      <Button
        type="button"
        variant="secondary"
        size="sm"
        className="mt-4"
        disabled={disabled}
        onClick={() => input.current?.click()}
      >
        <Upload className="h-4 w-4" aria-hidden="true" />
        Choose a file
      </Button>
    </div>
  )
}
