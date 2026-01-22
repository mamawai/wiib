import { create } from 'zustand';
import { persist } from 'zustand/middleware';
import type { User, Position } from '../types';
import { authApi, userApi } from '../api';

interface UserState {
  user: User | null;
  positions: Position[];
  token: string | null;
  loading: boolean;
  setUser: (user: User | null) => void;
  setPositions: (positions: Position[]) => void;
  setToken: (token: string | null) => void;
  fetchUser: () => Promise<void>;
  fetchPositions: () => Promise<void>;
  logout: () => Promise<void>;
}

export const useUserStore = create<UserState>()(
  persist(
    (set) => ({
      user: null,
      positions: [],
      token: null,
      loading: false,

      setUser: (user: User | null) => set({ user }),
      setPositions: (positions: Position[]) => set({ positions }),
      setToken: (token: string | null) => set({ token }),

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

      fetchPositions: async () => {
        try {
          const positions = await userApi.positions();
          set({ positions });
        } catch {
          set({ positions: [] });
        }
      },

      logout: async () => {
        try {
          await authApi.logout();
        } catch {
          // ignore
        }
        set({ user: null, positions: [], token: null });
      },
    }),
    {
      name: 'wiib-user',
      partialize: (state) => ({ token: state.token }),
    }
  )
);
