import { Navigate } from 'react-router-dom';

/** Reset links from before Keycloak (identity's e-mails) no longer work: ask Keycloak for a new one. */
export default function ResetPasswordPage() {
  return <Navigate to="/forgot-password" replace />;
}
