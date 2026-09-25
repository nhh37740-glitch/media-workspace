import { createApp } from 'vue'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import App from './App.vue'
import router from './router'
import './styles/main.css'

// The locale is fixed to Chinese because the task states are shown to the user in Chinese; the
// server keeps sending the English identifiers, and the translation happens in the view layer only.
createApp(App).use(router).use(ElementPlus, { locale: zhCn }).mount('#app')
