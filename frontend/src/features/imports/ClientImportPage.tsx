import { CLIENT_IMPORT } from './importWizard'
import { ImportWizardPage } from './ImportWizardPage'

/**
 * S-34 Client Import Wizard — blueprint §4B.3.
 *
 * One of the two registrations of {@link ImportWizardPage}. It used to be the
 * whole wizard; B-038 lifted the five steps out of it so the resource master
 * could have the same screen, which is what §4B.3's "build it once, register two
 * schemas" asks for on this side of the wire too.
 *
 * Kept as its own component rather than mounting `<ImportWizardPage
 * config={CLIENT_IMPORT} />` inline in the router, so that `/masters/clients/import`
 * and `/masters/resources/import` are two named screens rather than one screen
 * with an argument. The route table reads as a list of pages, and the tests that
 * have covered this one since B-031 did not have to change.
 */
export function ClientImportPage() {
  return <ImportWizardPage config={CLIENT_IMPORT} />
}
