import { create } from 'zustand';
import api from '../api/client';

interface DepositState {
  balance: number | null;
  blockedAmount: number | null;
  availableBalance: number | null;
  isLoading: boolean;
  fetchDeposit: () => Promise<void>;
  reset: () => void;
}

export const useDepositStore = create<DepositState>((set) => ({
  balance: null,
  blockedAmount: null,
  availableBalance: null,
  isLoading: false,

  fetchDeposit: async () => {
    set({ isLoading: true });
    try {
      const { data } = await api.get('/deposits/my');
      set({
        balance: data.balance,
        blockedAmount: data.blockedAmount,
        availableBalance: data.availableBalance ?? (data.balance - data.blockedAmount),
        isLoading: false,
      });
    } catch {
      set({ isLoading: false });
    }
  },

  reset: () => set({ balance: null, blockedAmount: null, availableBalance: null }),
}));
