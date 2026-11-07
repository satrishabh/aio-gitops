// Uses disposable test identities created by bootstrap/aio-isolation-smoke-users.sh.
// Pass their private temporary JSON file as argv[3]. Never print its contents.
const { chromium } = require(process.argv[2] || 'playwright')
const fs = require('node:fs/promises')
const assert = require('node:assert/strict')
;(async () => {
  const identities = JSON.parse(await fs.readFile(process.argv[3], 'utf8'))
  const browser = await chromium.launch({ channel: 'msedge', headless: true })
  try {
    const sessions = {}
    for (const identity of identities) {
      const context = await browser.newContext({ viewport: { width: 390, height: 844 } })
      const page = await context.newPage()
      await page.goto('https://app.sarvaikam.com/oauth2/authorization/profile')
      await page.locator('#username').fill(identity.username)
      await page.locator('#password').fill(identity.password)
      await page.locator('#kc-login').click()
      await page.waitForURL(url => url.hostname === 'app.sarvaikam.com' && !url.pathname.startsWith('/login'), { timeout: 60000 })
      sessions[identity.tier] = { page, context, identity }
    }
    const get = async (session, path) => {
      const response = await session.context.request.get(`https://app.sarvaikam.com${path}`)
      return { status: response.status(), body: await response.json() }
    }
    const admin = sessions.admin, business = sessions.business
    assert.equal((await get(admin, '/api/me')).body.accessTier, 'admin')
    assert.equal((await get(business, '/api/me')).body.accessTier, 'business')
    const all = await get(admin, '/api/projects')
    assert.equal(all.status, 200)
    assert.ok(all.body.length > 0, 'Admin can see existing projects')
    for (const path of ['/api/projects', '/api/runs', '/api/scans', '/api/promotions']) {
      const response = await get(business, path)
      assert.equal(response.status, 200)
      assert.deepEqual(response.body, [], `Unassigned user sees no foreign data at ${path}`)
    }
    const metrics = await get(business, '/api/metrics')
    assert.equal(metrics.body.overall.ciRuns, 0)
    assert.deepEqual(metrics.body.projects, {})
    const ownAudit = await get(business, '/api/audit')
    assert.equal(ownAudit.status, 200)
    assert.ok(ownAudit.body.every(event => event.actor === business.identity.username), 'Audit is scoped to current identity')
    assert.equal((await get(business, '/api/users')).status, 403)
    assert.equal((await get(business, '/api/clusters')).status, 403)
    const project = all.body[0].project
    assert.equal((await get(admin, `/api/projects/${project.id}`)).status, 200)
    for (const path of [`/api/projects/${project.id}`, `/api/projects/${project.id}/domains`, `/api/projects/${project.id}/environments/dev/cd`])
      assert.equal((await get(business, path)).status, 404, path)
    const run = all.body.find(item => item.lastRun?.id)?.lastRun
    if (run) {
      assert.equal((await get(admin, `/api/runs/${run.id}`)).status, 200)
      for (const path of [`/api/runs/${run.id}`, `/api/runs/${run.id}/stream`, `/api/runs/${run.id}/steps/1/logs`])
        assert.equal((await get(business, path)).status, 404, path)
    }
    const repos = await get(business, '/api/github/repos')
    assert.equal(repos.status, 200)
    assert.deepEqual(repos.body, [])
    await business.page.goto('https://app.sarvaikam.com/projects')
    assert.equal(await business.page.locator('.appearance-dock').count(), 0)
    await business.page.getByRole('button', { name: 'Profile and settings', exact: true }).click()
    await business.page.getByLabel('Appearance', { exact: true }).selectOption('dark')
    await business.page.waitForFunction(() => document.documentElement.dataset.theme === 'dark')
    await business.page.getByRole('button', { name: 'Close panel' }).click()
    await business.page.getByRole('button', { name: 'Notifications and audit activity' }).click()
    await business.page.getByRole('link', { name: 'View all audit logs' }).waitFor()
    console.log('PASS: live OIDC admin/business sessions, admin global access, regular-user empty scoped lists/metrics, own audit only, cross-user project/run/log/stream denial, admin-only users/clusters, private repo picker isolation, profile appearance and audit bell')
  } finally { await browser.close() }
})().catch(error => { console.error(error.message); process.exitCode = 1 })
