import { cn } from "@/lib/utils";

interface CursorLoginScreenProps {
  checking: boolean;
  waiting: boolean;
  error: string | null;
  isLight: boolean;
  dynamic: boolean;
  wallpaperUrl?: string | null;
  onLogin: () => void;
  onCancel: () => void;
}

export function CursorLoginScreen({
  checking,
  waiting,
  error,
  isLight,
  dynamic,
  wallpaperUrl,
  onLogin,
  onCancel,
}: CursorLoginScreenProps) {
  return (
    <div
      className={cn(
        "relative min-h-screen overflow-hidden",
        isLight ? "bg-[rgb(var(--app-light-surface-rgb))] text-[#1a1625]" : "bg-[#1a1625] text-white"
      )}
    >
      {dynamic && (
        <div data-app-backdrop="ambient" className="ambient-glow-container">
          <div className="ambient-glow ambient-glow-1" />
          <div className="ambient-glow ambient-glow-2" />
          <div className="ambient-glow ambient-glow-3" />
          <div className="ambient-glow ambient-glow-4" />
          <div className="ambient-glow ambient-glow-5" />
        </div>
      )}
      {wallpaperUrl && (
        <div
          data-app-backdrop="image"
          className="fixed inset-0 z-0 bg-cover bg-center bg-no-repeat"
          style={{ backgroundImage: `url(${wallpaperUrl})` }}
        />
      )}
      <main className="relative z-10 flex min-h-screen items-center justify-center px-6 py-16">
        <section
          className={cn(
            "w-full max-w-sm rounded-[28px] border px-6 py-7 backdrop-blur-[40px] saturate-180",
            isLight
              ? "bg-white/75 border-black/5 shadow-[0_16px_60px_rgba(0,0,0,0.10)]"
              : "bg-[#1a1625]/80 border-white/[0.08] shadow-[0_16px_60px_rgba(26,22,37,0.45)]"
          )}
        >
          <p className={cn("text-[11px] font-medium tracking-[0.16em]", isLight ? "text-[#1a1625]/45" : "text-white/45")}>
            SILLYCLIENT
          </p>
          <h1 className="mt-3 text-[28px] font-semibold leading-tight">登录 Cursor</h1>
          <p className={cn("mt-3 text-sm leading-6", isLight ? "text-[#1a1625]/70" : "text-white/70")}>
            使用 Cursor 账号继续。登录会打开 cursor.com，账号和密码只在 Cursor 的页面里输入。
          </p>

          {checking ? (
            <p className={cn("mt-8 text-sm", isLight ? "text-[#1a1625]/55" : "text-white/55")}>正在确认登录状态</p>
          ) : (
            <div className="mt-8 flex flex-col gap-3">
              <button
                type="button"
                onClick={onLogin}
                disabled={waiting}
                className={cn(
                  "motion-control h-11 rounded-full text-sm font-semibold disabled:opacity-60",
                  isLight ? "bg-[#1a1625] text-white" : "bg-white text-[#1a1625]"
                )}
              >
                {waiting ? "正在等待浏览器登录" : "使用 Cursor 账号登录"}
              </button>
              {waiting && (
                <>
                  <p className={cn("text-center text-xs leading-5", isLight ? "text-[#1a1625]/55" : "text-white/55")}>
                    在浏览器里完成确认后，回到这里即可。
                  </p>
                  <button
                    type="button"
                    onClick={onCancel}
                    className={cn(
                      "motion-control h-10 rounded-full text-sm",
                      isLight ? "text-[#1a1625]/70" : "text-white/70"
                    )}
                  >
                    取消
                  </button>
                </>
              )}
              {error && (
                <p role="alert" className="text-center text-xs leading-5 text-[#d4564e]">
                  {error}
                </p>
              )}
            </div>
          )}
        </section>
      </main>
    </div>
  );
}
