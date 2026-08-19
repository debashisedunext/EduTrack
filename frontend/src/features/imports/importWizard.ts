import type { ImportSchemaKey } from './importQueries'

/**
 * B-038 · what differs between importing clients and importing resources.
 *
 * Blueprint §4B.3 closes with *"the same wizard pattern is reused for the
 * resource master bulk import — build it once, register two schemas"*, and §7.5
 * says of S-34 that "the same component is registered a second time for resource
 * bulk import". This file is what "registered" means on this side of the wire.
 *
 * The backend made that structural: a registration is a Spring `@Component`
 * implementing one interface, and B-038 added no route, no branch and no edit to
 * the registry. The browser cannot go quite that far — a screen has a heading, a
 * Back link and sentences with a noun in them — so the next best thing is to make
 * the differences *enumerable*. Everything here is data. Anything that ends up
 * as `if (schema === 'clients')` in a component belongs in this file instead.
 *
 * ## Why the wording is configured rather than genericised
 *
 * The tempting shortcut is to write "records" everywhere and have one wizard with
 * no configuration at all. It reads as a stranger's software: the sentence that
 * matters most on this screen is *"nothing has been written — no client has
 * changed"*, and "no record has changed" is exactly the reassurance somebody
 * about to import four hundred rows does not get. The nouns cost two fields.
 *
 * ## Why the natural key's heading is here
 *
 * `Client Code` and `Employee Code` are named in the copy at three steps —
 * they are the answer to "how do I stop this creating duplicates?", which is the
 * question a bulk import is actually judged on. The heading is the template's,
 * so it is the one the user is looking at in their spreadsheet, and it comes
 * from the server's `/fields` for the mapping dropdowns. Repeating it in prose is
 * a copy, which is why it is one copy in one place rather than six.
 */

/**
 * What this registration calls the thing being imported.
 *
 * Its own type because the wizard's steps take it on its own: `MappingStep` and
 * `ValidationStep` need the noun and nothing else on the config, and passing them
 * the whole thing would let a step reach for `schema` or `master.href` and grow a
 * dependency it does not have.
 */
export interface ImportNouns {
  /** "client" — the singular used in "no client has changed". */
  one: string
  /** "clients" — the plural, and the word in "Which sheet holds the clients?". */
  many: string
  /**
   * The natural key's template heading — "Client Code", "Employee Code".
   *
   * The upsert key, named in the copy because "existing records are updated,
   * never duplicated" is meaningless until the user knows *what it matches on*.
   */
  keyHeading: string
}

export interface ImportWizardConfig {
  /**
   * The URL segment, which the contract's `ImportSchema` enum constrains to
   * `clients` or `users`.
   *
   * Note `users`, not `resources`: the task is titled "resource bulk import" and
   * the screen is the Resource Master, but the path is what has to be right and
   * the generated client has called it since D-001.
   */
  schema: ImportSchemaKey

  /**
   * `import_batches.entity` — what the history panel filters on.
   *
   * Deliberately not derived from {@link schema}. They are different things on
   * purpose (a URL segment versus a stored discriminator), and reconstructing one
   * from the other here would put back the coupling the server took out.
   */
  entity: 'CLIENT' | 'RESOURCE'

  /** The page heading. */
  title: string

  /** Where Back goes, and what the master is called there. */
  master: { href: string; label: string }

  nouns: ImportNouns

  /**
   * Step 1's list of what is in the template.
   *
   * Per-schema because the interesting item is the one naming this schema's own
   * dropdowns, and a generic "some columns have dropdowns" tells nobody anything.
   */
  templateHighlights: string[]
}

/** S-34 · the client master, blueprint §4B.3 — B-031…B-037. */
export const CLIENT_IMPORT: ImportWizardConfig = {
  schema: 'clients',
  entity: 'CLIENT',
  title: 'Import clients from Excel',
  master: { href: '/masters/clients', label: 'clients' },
  nouns: { one: 'client', many: 'clients', keyHeading: 'Client Code' },
  templateHighlights: [
    'Every column the client master accepts, in order.',
    'Dropdowns on Status and Support Plan — the same values the import accepts, so a chosen value is never rejected later.',
    'One filled example row. Replace it with your own data or delete it; it is imported like any other row.',
    'An Instructions sheet naming the required columns and what Client Code does: a code that already exists updates that client, it never creates a second one.',
  ],
}

/**
 * S-07 · the resource master, blueprint §7.4 — B-038.
 *
 * The second registration. Nothing in this object is a component, a route or a
 * branch; that is the whole claim the task makes.
 */
export const RESOURCE_IMPORT: ImportWizardConfig = {
  schema: 'users',
  entity: 'RESOURCE',
  title: 'Import resources from Excel',
  master: { href: '/masters/resources', label: 'resources' },
  nouns: { one: 'resource', many: 'resources', keyHeading: 'Employee Code' },
  templateHighlights: [
    'Every column the resource master accepts, in order.',
    'A dropdown on Role — the same six codes the import accepts, so a chosen role is never rejected later.',
    'One filled example row. Replace it with your own data or delete it; it is imported like any other row.',
    // The two questions an admin asks about a bulk-created account, answered
    // before they upload rather than after somebody cannot log in.
    'No password column. Imported people are created with a password nobody knows and reach their account through “Forgotten password”.',
    'An Instructions sheet naming the required columns and what Employee Code does: a code that already exists updates that person, it never creates a second account.',
  ],
}
