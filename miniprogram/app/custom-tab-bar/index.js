const tabs = [
  { key: 'home', text: '工作台', icon: 'home' },
  { key: 'operations', text: '出入库', icon: 'operations' },
  { key: 'inventory', text: '库存', icon: 'inventory', admin: true },
  { key: 'history', text: '记录', icon: 'history' },
]
Component({
  data: { selected: 'home', tabs: tabs.filter(tab => !tab.admin) },
  methods: {
    update(selected, role) { this.setData({ selected, tabs: tabs.filter(tab => !tab.admin || role === 'ADMIN') }) },
    switchTab(event) {
      const key = event.currentTarget.dataset.key
      if (this.data.tabs.some(tab => tab.key === key) && key !== this.data.selected) wx.switchTab({ url: '/pages/' + key + '/index' })
    },
  },
})
