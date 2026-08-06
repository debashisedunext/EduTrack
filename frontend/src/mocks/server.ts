import { setupServer } from 'msw/node';
import { handlers } from './handlers';

/** The node server, used by vitest. See src/test/setup.ts. */
export const server = setupServer(...handlers);
