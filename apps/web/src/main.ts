import { createApp } from 'vue'
import { createPinia } from 'pinia'

import App from './majo/MajoApp.vue'
import router from './router'
import './majo/style.css'

const app = createApp(App)

app.use(createPinia())
app.use(router)

app.mount('#app')
