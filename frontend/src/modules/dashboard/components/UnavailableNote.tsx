import { unavailableLabels } from "../utils/dashboardFormat";

/**
 * Says which figures are missing because their service did not answer (it is starting, slow or down), so an empty
 * panel is not read as "nothing to show". Nothing is rendered when every figure arrived.
 */
export default function UnavailableNote({
  sections,
  onRefresh,
  refreshing,
}: {
  sections?: string[];
  onRefresh: () => void;
  refreshing: boolean;
}) {
  const labels = unavailableLabels(sections);
  if (labels.length === 0) return null;

  return (
    <div
      role="status"
      className="mb-6 flex flex-col gap-2 rounded-xl border border-warning-200 bg-warning-50 px-4 py-3 text-sm text-warning-700 sm:flex-row sm:items-center sm:justify-between dark:border-warning-500/30 dark:bg-warning-500/10 dark:text-warning-400"
    >
      <p>
        Some figures are temporarily unavailable: {labels.join(", ")}. A service did not answer in time; the rest of the
        dashboard is up to date.
      </p>
      <button
        type="button"
        onClick={onRefresh}
        disabled={refreshing}
        className="shrink-0 self-start font-medium underline underline-offset-2 disabled:opacity-50 sm:self-auto"
      >
        {refreshing ? "Refreshing..." : "Try again"}
      </button>
    </div>
  );
}
