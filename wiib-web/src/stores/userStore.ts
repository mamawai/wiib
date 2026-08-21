import { create } from 'zustand';
import { persist } from 'zustand/middleware';
import type { User } from '../types';
import { authApi, userApi } from '../api';
import { currentLang } from '../i18n';
import { reconnectWithIdentity } from '../hooks/stompClient';

interface UserState {
  user: User | null;
  token: string | null;
  loading: boolean;
  setUser: (user: User | null) => void;
  setToken: (token: string | null) => void;
  fetchUser: () => Promise<void>;
  logout: () => Promise<void>;
}

export const useUserStore = create<UserState>()(
  persist(
    (set) => ({
      user: null,
      token: null,
      loading: false,

      setUser: (user: User | null) => set({ user }),
      // token 变了必须重连 WS：身份是建连时 STOMP CONNECT 帧里的 token 认的，不重连就还挂着
      // 旧身份，表现为登录后通知角标永远 0 且不报错。persist 是同步写，重连读到的已是新值
      setToken: (token: string | null) => {
        set({ token });
        reconnectWithIdentity();
        // 登录即把本地语言推给服务端：游客期切的语言也靠这一下带过去，否则英文用户注册完
        // 第一份 AI 产出还是中文。localStorage 始终是唯一事实源，不做反向同步
        if (token) {
          void userApi.setLang(currentLang()).catch(() => {});
        }
      },

      fetchUser: async () => {
        try {
          set({ loading: true });
          const user = await authApi.current();
          set({ user });
        } catch {
          set({ user: null, token: null });
        } finally {
          set({ loading: false });
        }
      },

      logout: async () => {
        try {
          await authApi.logout();
        } catch {
          // ignore
        }
        set({ user: null, token: null });
        reconnectWithIdentity();   // 退回匿名身份，否则登出后仍能收到上一个账号的通知
      },
    }),
    {
      name: 'wiib-user',
      partialize: (state) => ({ token: state.token }),
    }
  )
);
