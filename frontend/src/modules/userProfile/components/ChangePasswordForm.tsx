import { useState } from 'react';
import Button from '../../../shared/components/ui/button/Button';
import useAuth from '../../auth/hooks/useAuth';

/** The password lives in Keycloak: its own page asks for the current one and the new one, then comes back here. */
export default function ChangePasswordForm() {
  const { changePassword } = useAuth();
  const [error, setError] = useState<string | null>(null);

  const handleClick = async () => {
    setError(null);
    try {
      await changePassword();
    } catch {
      setError('The password page is unavailable. Try again in a moment.');
    }
  };

  return (
    <div className="flex flex-col gap-4">
      {error && (
        <div className="rounded-lg border border-error-200 bg-error-50 px-4 py-3 text-sm text-error-700 dark:border-error-800 dark:bg-error-500/10 dark:text-error-400">
          {error}
        </div>
      )}
      <p className="text-sm text-gray-500 dark:text-gray-400">
        You will choose your new password on the sign-in page, then come back here.
      </p>
      <div>
        <Button size="sm" onClick={handleClick}>
          Change password
        </Button>
      </div>
    </div>
  );
}
