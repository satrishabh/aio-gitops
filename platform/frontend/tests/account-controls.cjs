const { chromium } = require(process.argv[2] || 'playwright')
const assert = require('node:assert/strict')
const { join } = require('node:path')
const { tmpdir } = require('node:os')

;(async () => {
  const browser = await chromium.launch({ channel: 'msedge', headless: true })
  try {
    const context = await browser.newContext({ viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true })
    const page = await context.newPage()
    let tier = 'business', auditRequests = 0, failAudit = false
    const errors = []
    page.on('pageerror', error => errors.push(error.message))
    await page.route('**/api/**', route => {
      const url = new URL(route.request().url())
      if (url.pathname === '/api/me') return route.fulfill({ json: { name: 'Alice Example', login: 'alice', accessTier: tier } })
      if (url.pathname === '/api/audit') {
        auditRequests++
        return route.fulfill(failAudit ? { status: 503, json: { message: 'Temporarily unavailable' } }
          : { json: [{ id: 1, actor: 'alice', action: 'run.trigger', target: 'alice-app#1', details: 'Manual build', createdAt: new Date().toISOString() }] })
      }
      return route.fulfill({ json: [] })
    })
    await page.goto('http://127.0.0.1:4173/projects')
    assert.equal(await page.locator('.appearance-dock').count(), 0, 'No floating theme control in workspace')
    const profile = page.getByRole('button', { name: 'Profile and settings', exact: true })
    await profile.tap()
    const panel = page.getByRole('dialog', { name: 'Profile and settings' })
    assert.ok(await panel.getByText('Alice Example', { exact: true }).isVisible())
    assert.ok(await panel.getByText('@alice', { exact: true }).isVisible())
    for (const mode of ['dark', 'light', 'system']) {
      await page.getByLabel('Appearance', { exact: true }).selectOption(mode)
      assert.equal(await page.getByLabel('Appearance', { exact: true }).inputValue(), mode)
    }
    await page.keyboard.press('Escape')
    assert.equal(await panel.count(), 0)
    assert.equal(await profile.evaluate(el => el === document.activeElement), true)
    const bell = page.getByRole('button', { name: 'Notifications and audit activity' })
    await bell.tap()
    await page.getByText('alice-app#1', { exact: false }).waitFor()
    for (const width of [320, 390, 760, 1440]) {
      await page.setViewportSize({ width, height: 844 })
      const bounds = await page.getByRole('dialog', { name: 'Notifications and audit activity' }).boundingBox()
      assert.ok(bounds.x >= 0 && bounds.x + bounds.width <= width, `Panel fits ${width}px`)
      assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true)
    }
    await page.setViewportSize({ width: 390, height: 844 })
    await page.screenshot({ path: join(tmpdir(), 'aio-audit-bell.png'), fullPage: true, animations: 'disabled' })
    await page.getByRole('link', { name: 'View all audit logs' }).click()
    await page.waitForURL('**/audit')
    assert.equal(await page.getByRole('dialog').count(), 0)
    await profile.tap()
    await page.screenshot({ path: join(tmpdir(), 'aio-profile-settings.png'), fullPage: true, animations: 'disabled' })
    await page.getByRole('button', { name: 'Close panel' }).click()
    failAudit = true
    await bell.tap()
    await page.getByText('Temporarily unavailable', { exact: true }).waitFor()
    failAudit = false
    await page.getByRole('button', { name: 'Retry', exact: true }).click()
    await page.getByText('alice-app#1', { exact: false }).first().waitFor()
    tier = 'community'
    await page.goto('http://127.0.0.1:4173/projects')
    assert.equal(await bell.count(), 0, 'Community users do not receive an unauthorized audit control')
    assert.ok(await profile.isVisible())
    const before = auditRequests
    await profile.tap()
    assert.equal(auditRequests, before, 'Profile does not fetch audit data')
    assert.deepEqual(errors, [])
    console.log('PASS: profile details, appearance settings, no floating theme icon, scoped audit bell UI, error/retry, audit navigation, focus restoration, 320–1440px panels, community permissions')
  } finally { await browser.close() }
})().catch(error => { console.error(error); process.exitCode = 1 })
