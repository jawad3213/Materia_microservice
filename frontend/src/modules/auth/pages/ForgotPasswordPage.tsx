import { useEffect } from 'react';
import PageMeta from '../../../shared/components/common/PageMeta';
import AuthLayout from '../layout/AuthLayout';
import authService from '../services/authService';

/** Keycloak resets passwords: its page asks for the e-mail and sends the link. */
export default function ForgotPasswordPage() {
  useEffect(() => {
    window.location.assign(authService.forgotPasswordUrl());
  }, []);

  return (
    <AuthLayout backgroundImage="https://ik.imagekit.io/jaouad/resetpassword.jpg">
      <PageMeta
        title="Forgot Password | Materia Admin"
        description="Reset your password to regain access to your Materia account."
      />
      <div className="flex flex-col items-center gap-4 text-center">
        <div className="h-10 w-10 animate-spin rounded-full border-4 border-brand-500 border-t-transparent" />
        <p className="text-sm text-gray-500 dark:text-gray-400">Redirecting to the password reset page…</p>
      </div>
    </AuthLayout>
  );
}
