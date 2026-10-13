import type { TurnSubmissionOptions, VerificationPlanView } from "./api/client";

export type Draft = { acceptanceDescription: string; assertionId: string; fileName: string; content: string; command: string };
export function prepareVerificationOptions(plan: VerificationPlanView, mode: string,
  draft: Draft, confirmed: boolean): TurnSubmissionOptions | null {
  if (mode === "INHERIT") return { projectPlanDigest: plan.digest };
  if (mode === "GENERAL") return { projectPlanDigest: plan.digest, verification: { purpose: "GENERAL" } };
  if (mode !== "DEFECT_FIX" || !confirmed || !draft.acceptanceDescription.trim()
      || draft.acceptanceDescription.length > 2000 || draft.acceptanceDescription.includes("\0")
      || !/^[A-Za-z][A-Za-z0-9_.-]{0,79}$/.test(draft.assertionId)
      || !/^[A-Za-z][A-Za-z0-9_.-]{0,79}$/.test(draft.fileName) || draft.fileName.includes("..")
      || !draft.content.trim() || draft.content.includes("\0")
      || new TextEncoder().encode(draft.content).length > 16384
      || !draft.command.trim() || draft.command.includes("\0") || draft.command.length > 4096) return null;
  const { acceptanceDescription, ...defectReproduction } = draft;
  return { projectPlanDigest: plan.digest,
    verification: { purpose: "DEFECT_FIX", acceptanceDescription, defectReproduction } };
}

