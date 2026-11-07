// Usage: node tests/live-appearance.cjs <playwright package path> [base URL]
const { chromium } = require(process.argv[2] || 'playwright')
const assert = require('node:assert/strict')
const { join } = require('node:path')
const { tmpdir } = require('node:os')

;(async () => {
  const browser = await chromium.launch({ channel: 'msedge', headless: true })
  try {
    const context = await browser.newContext({ colorScheme: 'light' })
    const page = await context.newPage()
    const errors = []
    page.on('pageerror', error => errors.push(error.message))
    const base = process.argv[3] || 'https://app.sarvaikam.com'
    const response = await page.goto(`${base}/login`)
    assert.equal(response.status(), 200)
    const theme = () => page.locator('html').getAttribute('data-theme')
    const reveal = async () => {
      const trigger = page.getByRole('button', { name: /^Appearance:/ })
      if (await trigger.getAttribute('aria-expanded') !== 'true') {
        await trigger.focus()
        await page.keyboard.press('ArrowDown')
      }
    }
    const choose = async name => {
      await reveal()
      await page.getByRole('radio', { name, exact: true }).click()
      await page.waitForFunction(expected => document.documentElement.dataset.theme === expected, name.startsWith('Dark') ? 'dark' : 'light')
    }
    await choose('Dark appearance')
    const darkHero = await page.locator('.login-hero').evaluate(el => getComputedStyle(el).backgroundColor)
    await page.reload()
    assert.equal(await theme(), 'dark', 'Dark preference survives reload')
    const second = await context.newPage()
    await second.goto(`${base}/login`)
    await choose('Light appearance')
    await second.waitForFunction(() => document.documentElement.dataset.theme === 'light')
    const lightHero = await page.locator('.login-hero').evaluate(el => getComputedStyle(el).backgroundColor)
    assert.notEqual(darkHero, lightHero, 'Login hero adapts to appearance')
    await reveal()
    await page.getByRole('radio', { name: 'Automatic — follow device appearance', exact: true }).click()
    await page.emulateMedia({ colorScheme: 'dark' })
    await page.waitForFunction(() => document.documentElement.dataset.theme === 'dark')
    await page.emulateMedia({ colorScheme: 'light' })
    await page.waitForFunction(() => document.documentElement.dataset.theme === 'light')
    await page.getByRole('radio', { name: 'Automatic — follow device appearance', exact: true }).focus()
    await page.keyboard.press('End')
    assert.equal(await theme(), 'dark', 'End selects Dark')
    await page.keyboard.press('Home')
    assert.equal(await theme(), 'light', 'Home selects Auto')
    await page.keyboard.press('Escape')
    assert.equal(await page.getByRole('button', { name: /^Appearance:/ }).getAttribute('aria-expanded'), 'false')
    assert.equal(await page.getByRole('radiogroup', { name: 'Appearance' }).isVisible(), false, 'Choices collapse')
    await page.mouse.move(0, 0)
    await page.getByRole('button', { name: /^Appearance:/ }).hover()
    assert.equal(await page.getByRole('radiogroup', { name: 'Appearance' }).isVisible(), true, 'Hover reveals choices')
    await page.mouse.move(0, 0)
    await page.locator('h1').click()
    assert.equal(await page.getByRole('radiogroup', { name: 'Appearance' }).isVisible(), false, 'Outside click dismisses choices')
    for (const width of [1920, 1440, 1280, 1100, 1024, 768, 761, 760, 390, 320]) {
      await page.setViewportSize({ width, height: 900 })
      for (const mode of ['Light appearance', 'Dark appearance']) {
        await choose(mode)
        assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true, `No page overflow at ${width}px in ${mode}`)
        const hero = await page.locator('.login-hero').boundingBox()
        const panel = await page.locator('.login-panel').boundingBox()
        if (width > 760) {
          assert.ok(Math.abs(hero.y - panel.y) < 1, `Desktop columns share a row at ${width}px`)
          assert.ok(panel.x >= hero.x + hero.width - 1, `Sign-in sits beside the introduction at ${width}px`)
          assert.ok(panel.width > width * .35, `Sign-in has usable space at ${width}px`)
        } else {
          assert.ok(panel.y < hero.y, `Mobile keeps sign-in first at ${width}px`)
        }
      }
    }
    await page.keyboard.press('Escape')
    const heading = await page.getByRole('heading', { name: 'Welcome to AIO' }).boundingBox()
    assert.ok(heading.y < 900, 'Mobile sign-in is visible without scrolling')
    await page.screenshot({ path: join(tmpdir(), 'aio-live-dark-mobile.png'), fullPage: true, animations: 'disabled' })
    await page.setViewportSize({ width: 1440, height: 1000 })
    await choose('Light appearance')
    await page.keyboard.press('Escape')
    await page.getByRole('heading', { name: 'Welcome to AIO' }).click()
    await page.screenshot({ path: join(tmpdir(), 'aio-live-light-desktop.png'), fullPage: true, animations: 'disabled' })
    assert.deepEqual(errors, [], 'No browser JavaScript errors')
    console.log(JSON.stringify({ result: 'PASS', checks: ['login HTTP 200', 'Light/Dark hero', 'reload persistence', 'cross-tab sync', 'live system changes', 'Home/End keyboard selection', 'hover reveal and Escape/outside dismissal', 'mobile sign-in above fold', '320–1920px layouts in both modes; desktop columns and mobile order verified', 'no JavaScript errors'], screenshots: [join(tmpdir(), 'aio-live-dark-mobile.png'), join(tmpdir(), 'aio-live-light-desktop.png')] }, null, 2))
  } finally {
    await browser.close()
  }
})().catch(error => { console.error(error); process.exitCode = 1 })
