import i18n, { type Lang } from '../i18n';
import { userApi } from '../api';
import { useUserStore } from '../stores/userStore';

/**
 * 切到某门语言：界面立刻换，服务端那份（只管 AI 产出语言）顺带推一次。
 * <p>
 * 顶栏切换器与首次进门的 LanguageGate 共用这一处——两边各写一份，迟早改一处忘另一处。
 * <p>
 * 单独一个模块而不是挂在某个组件文件里：组件文件混着导出非组件会破坏 fast refresh；
 * 也不能放进 src/i18n —— api 已经 import 了它，反向引用会成环。
 */
export function applyLanguage(lang: Lang) {
  void i18n.changeLanguage(lang);
  // 游客没账号可写就不调；失败也不打扰用户——界面语言本地已经切好了，
  // 最坏结果只是 AI 还说上一门语言，下次切换/登录会再推一次
  if (useUserStore.getState().token) {
    void userApi.setLang(lang).catch(() => {});
  }
}
