import { setupWorker } from 'msw/browser';
import { handlers } from './handlers';

/** The browser worker. Started from main.tsx when VITE_USE_MOCKS is on. */
export const worker = setupWorker(...handlers);
