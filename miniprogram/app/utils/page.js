async function guard(page, selected, admin = false) {
  try {
    const app = getApp()
    // Selection belongs to navigation, even when the page cannot load its data.
    const bar = selected && page.getTabBar && page.getTabBar()
    if (bar) bar.update(selected, app.user && app.user.role)
    await app.ready()
    if (app.user.approved === false) {
      app.openPending()
      return null
    }
    if (admin && app.user.role !== 'ADMIN') {
      wx.switchTab({ url: '/pages/home/index' })
      return null
    }
    if (bar) bar.update(selected, app.user.role)
    return app
  } catch (error) {
    page.setData({ error: error.message, loading: false })
    return null
  }
}
module.exports = { guard }
