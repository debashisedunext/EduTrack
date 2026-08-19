import { RESOURCE_IMPORT } from './importWizard'
import { ImportWizardPage } from './ImportWizardPage'

/**
 * B-038 · resource bulk import — blueprint §7.4's S-07 "bulk import via CSV",
 * on §4B.3's wizard.
 *
 * <b>This file is the frontend half of the deliverable's evidence.</b> The
 * backend's claim is that a second importable entity costs one `@Component`; the
 * browser's is that it costs one route and one config object. Everything a user
 * sees here — the five steps, the dropzone, the sheet selector, the mapping
 * table with its presets, the per-row dry run, the progress bar, the error report
 * and B-037's reversible history — is the same component tree S-34 renders,
 * asking the server for `users` instead of `clients`.
 *
 * The wording differs, and deliberately so: see `importWizard.ts` for why "no
 * resource has changed" is worth two configured nouns rather than being
 * genericised into "no record has changed".
 */
export function ResourceImportPage() {
  return <ImportWizardPage config={RESOURCE_IMPORT} />
}
