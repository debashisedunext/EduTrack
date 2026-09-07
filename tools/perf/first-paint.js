// =====================================================================
// A-073 · dashboard first paint — the browser half
//
// PLAN.md §6's M6 exit is "dashboard first paint under 1.5 s on a seeded
// 50,000-ticket dataset". `k6/dashboard.js` has owned the server half of
// that sentence since it was written, and its header has pointed here —
// "the browser half is measured by tools/perf/first-paint.js" — at a file
// that did not exist. The README said the same thing in its limitations:
// "First paint is measured in two halves. k6 measures the server; the
// browser half — bundle, parse, render — is not covered here."
//
// So the budget's larger half was a decision nobody had measured:
//
//   ~1000 ms   document + bundle + parse + render   <- THIS FILE
//   ~500 ms    the API calls that block first paint  (k6/dashboard.js)
//
// This closes it. The split stays a decision; what changes is that both
// sides of it are now observed rather than one.
//
// WHY A REAL BROWSER AND NOT A SYNTHETIC NUMBER
//
// The browser half is bundle transfer, parse, execute, React's first
// render and the paint itself. None of that is reachable from an HTTP
// client — `http.get('/')` measures the 1.5 kB document and stops, which
// is precisely the measurement that would look excellent while the thing
// being budgeted got worse. k6's browser module drives real Chromium and
// reports the same web vitals a field measurement would.
//
// It needs Chromium, which `grafana/k6:latest` does not carry. run.sh
// uses `grafana/k6:master-with-browser` for this target only, keeping the
// existing two scripts on the smaller image.
//
// WHAT IS ASSERTED
//
// FCP, at p95, under the 1000 ms this half of the budget was given. FCP
// rather than LCP is the assertion because "first paint" is what M6 says
// and FCP is its literal reading: the first frame carrying content. LCP
// is recorded beside it and deliberately not thresholded — on a dashboard
// the largest element is a chart that cannot draw until its widget call
// returns, so an LCP threshold here would be re-asserting
// k6/dashboard.js's 500 ms through a noisier instrument.
//
// FEWER ITERATIONS THAN THE API TESTS, ON PURPOSE
//
// dashboard.js runs 40 VUs for 90 s because queueing is the effect it is
// looking for. A browser VU is a Chromium process; forty of them measure
// the host's core count rather than the application. First paint is a
// per-user experience, so this measures one user at a time and repeats.
//
// WHAT THIS DOES NOT ESTABLISH
//
// The same caveat the README already carries for the server half, and it
// is not weaker here: this is a local machine, over loopback, with a warm
// cache and a warm buffer pool, sharing a host with MySQL, Redis, MinIO
// and the API. It cannot show that production hardware paints in 1.5 s.
// What it can show is the bundle-and-render cost of a change, measured
// the same way before and after — which is what a budget is for.
//
// RUN
//   tools/perf/run.sh first-paint
// =====================================================================

import { browser } from 'k6/browser'
import { Trend } from 'k6/metrics'
import { check } from 'k6'

const BASE_URL = __ENV.BASE_URL || 'http://host.docker.internal:8080'

// The route under test. `/dashboard` deep-links through SpaResourceConfig
// to the same index.html a cold visit gets, which is the case being
// budgeted — not an in-app navigation, which pays none of the bundle cost.
const ROUTE = `${BASE_URL}/dashboard`

// Recorded alongside k6's own browser_web_vital_* metrics. These come
// from the Navigation Timing and Paint Timing entries in the page, and
// exist to say *where* the time went when the threshold fails — a red FCP
// with no breakdown tells you the budget broke and nothing about why.
const docMs = new Trend('fp_document_ms', true)
const domInteractiveMs = new Trend('fp_dom_interactive_ms', true)
const scriptBytes = new Trend('fp_script_transfer_bytes')
const scriptMs = new Trend('fp_script_time_ms', true)

// FCP AND LCP ARE READ OUT OF THE PAGE, NOT TAKEN FROM k6
//
// The first version of this file asserted on k6's own
// `browser_web_vital_fcp`. It reported p(95)=0s and the threshold passed —
// a green run that had measured nothing, because k6 collects vitals
// asynchronously and this iteration closes the page as soon as `load`
// fires, before they are flushed.
//
// A threshold that passes at zero is worse than no threshold: it is the
// A-068 defect this repo already has a name for, a 100% figure from a
// counter nothing incremented. So the numbers that carry the assertion
// are pulled from the page's own Paint Timing and LargestContentfulPaint
// entries, where an absent value is absent rather than zero, and the
// checks below refuse an iteration that produced none.
const fcpMs = new Trend('fp_fcp_ms', true)
const lcpMs = new Trend('fp_lcp_ms', true)

export const options = {
  scenarios: {
    first_paint: {
      executor: 'per-vu-iterations',
      vus: 1,
      iterations: 10,
      maxDuration: '5m',
      options: {
        browser: { type: 'chromium' },
      },
    },
  },
  thresholds: {
    // THE ASSERTION. This half of the M6 budget, measured rather than
    // assumed. A red run here is the browser half failing.
    fp_fcp_ms: ['p(95)<1000'],
    // Recorded, not asserted — see the header.
    fp_lcp_ms: ['p(95)<4000'],
    // Not decoration. `fp_fcp_ms` having no samples at all would leave its
    // threshold trivially satisfied, so the checks are what refuse a run
    // that measured nothing. Both have to be green for the run to mean
    // anything.
    checks: ['rate>0.99'],
  },
}

export default async function () {
  const page = await browser.newPage()
  try {
    // `load` rather than `networkidle`: first paint happens well before
    // the widget fan-out settles, and waiting for idle would fold
    // k6/dashboard.js's 500 ms back into this number — measuring the
    // same API twice and calling it a browser cost.
    await page.goto(ROUTE, { waitUntil: 'load' })

    // LCP is only final once the page stops producing larger elements, and
    // FCP is emitted a tick after the frame it names. Reading both
    // immediately after `load` is what returned zeroes the first time. This
    // waits for a contentful paint to exist rather than sleeping a fixed
    // amount, so it costs nothing on a fast page and does not invent a
    // number on a slow one.
    await page.waitForFunction(
      () => performance.getEntriesByName('first-contentful-paint').length > 0,
      { timeout: 10000, polling: 100 },
    )

    const timings = await page.evaluate(() => {
      const nav = performance.getEntriesByType('navigation')[0] || {}
      const scripts = performance
        .getEntriesByType('resource')
        .filter((r) => r.initiatorType === 'script')
      const fcp = performance.getEntriesByName('first-contentful-paint')[0]
      return {
        document: nav.responseEnd ? nav.responseEnd - nav.startTime : 0,
        domInteractive: nav.domInteractive || 0,
        scriptBytes: scripts.reduce((n, r) => n + (r.transferSize || 0), 0),
        scriptMs: scripts.reduce((n, r) => n + r.duration, 0),
        // null, never 0, when the entry is absent — so a missing
        // measurement fails a check instead of recording a perfect score.
        fcp: fcp ? fcp.startTime : null,
        title: document.title,
      }
    })

    // LCP needs a buffered PerformanceObserver, not getEntriesByType —
    // Chrome exposes no entry list for it, which is why the first attempt
    // recorded nothing and its threshold passed on an empty metric. The
    // observer replays what already happened, so this reads the real value
    // rather than waiting for a new one.
    const lcp = await page.evaluate(
      () =>
        new Promise((resolve) => {
          let latest = null
          const observer = new PerformanceObserver((list) => {
            for (const entry of list.getEntries()) latest = entry.startTime
          })
          observer.observe({ type: 'largest-contentful-paint', buffered: true })
          setTimeout(() => {
            observer.disconnect()
            resolve(latest)
          }, 300)
        }),
    )

    docMs.add(timings.document)
    domInteractiveMs.add(timings.domInteractive)
    scriptBytes.add(timings.scriptBytes)
    scriptMs.add(timings.scriptMs)
    if (timings.fcp !== null) fcpMs.add(timings.fcp)
    if (lcp !== null) lcpMs.add(lcp)

    check(timings, {
      // The empty-page trap, in the shape the other two scripts use: a
      // blank document paints beautifully. If nothing rendered, every
      // number above is excellent and meaningless.
      'first contentful paint was recorded': (t) => t.fcp !== null && t.fcp > 0,
      'largest contentful paint was recorded': () => lcp !== null && lcp > 0,
      'the app shell rendered': (t) => t.title.length > 0,
      'a script bundle was transferred': (t) => t.scriptBytes > 0,
    })
  } finally {
    await page.close()
  }
}
