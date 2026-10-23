import { useEffect, useRef } from 'react';
import { useLocation } from 'react-router-dom';
import PageMeta from '../../../shared/components/common/PageMeta';
import AuthLayout from '../layout/AuthLayout';
import useAuth from '../hooks/useAuth';

/** Sign-in is Keycloak's page: this one sends the browser there, and back to where the user was going. */
export default function LoginPage() {
  const { login, error } = useAuth();
  const location = useLocation();
  const from = (location.state as { from?: { pathname?: string; search?: string } } | null)?.from;
  const redirectPath = from?.pathname ? `${from.pathname}${from.search ?? ''}` : '/dashboard';
  const started = useRef(false);

  const signIn = () => {
    login(redirectPath).catch(() => undefined);
  };

  useEffect(() => {
    if (!started.current) {
      started.current = true;
      signIn();
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  return (
    <AuthLayout backgroundImage="https://ik.imagekit.io/jaouad/pexels-tiger-lily-4483772.jpg">
      <PageMeta
        title="Sign In | Materia Admin"
        description="Sign in to your Materia account to manage raw materials, suppliers, and categories."
      />
      <div className="flex flex-col items-center gap-4 text-center">
        {error ? (
          <>
            <p className="text-sm text-error-600 dark:text-error-400">{error}</p>
            <button
              onClick={signIn}
              className="rounded-lg bg-brand-500 px-4 py-2 text-sm font-medium text-white hover:bg-brand-600"
            >
              Try again
            </button>
          </>
        ) : (
          <>
            <div className="h-10 w-10 animate-spin rounded-full border-4 border-brand-500 border-t-transparent" />
            <p className="text-sm text-gray-500 dark:text-gray-400">Redirecting to the sign-in page…</p>
          </>
        )}
      </div>
    </AuthLayout>
  );
}
