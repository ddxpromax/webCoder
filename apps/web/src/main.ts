import { createApp } from 'vue'
import { createPinia } from 'pinia'

import App from './majo/MajoApp.vue'
import router from './router'
import './majo/style.css'
import { installPluginHost } from './majo/pluginRuntime'
import { loadFrontendPlugins } from './majo/pluginLoader'

const app = createApp(App)

installPluginHost()
app.use(createPinia())
app.use(router)

app.mount('#app')

router.isReady().then(() => void loadFrontendPlugins())
window.addEventListener('majo-plugins-changed', () => void loadFrontendPlugins())
