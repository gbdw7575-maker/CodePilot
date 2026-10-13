import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import type { CSSProperties, ReactNode } from "react";
import { TaskCheckPreviewPanel } from "./TaskCheckPreviewPanel";
import { TaskAcceptanceModePanel } from "./TaskAcceptanceModePanel";
import { taskCheckPreviewScopeKey } from "./api/client";
import {
  artifactContentUrl,
  appendTurnInput,
  cancelRun,
  createModelProfile,
  createProject,
  createThread,
  decideApproval,
  deleteProject,
  deleteThread,
  EventStreamClient,
  foldPlans,
  listAllEvents,
  newerSequence,
  getDiff,
  getRun,
  listApprovals,
  listChildRuns,
  listFsEntries,
  listMessages,
  listModelProfiles,
  listPlans,
  listProjects,
  listThreads,
  listVerifications,
  saveCredential,
  submitTurn,
  getRunVerificationPlan,
  trustProject,
  validateModelProfile,
  type ApiError,
  type Approval,
  type Diff,
  type EventEnvelope,
  type FsListing,
  type MessageItem,
  type ModelProfile,
  type PlanView,
  type Project,
  type Run,
  type Thread,
  type Verification,
  type TurnSubmissionOptions,
  type VerificationPlanView,
} from "./api/client";

import { VerificationRequirementEditor, VerificationPlanSummary } from "./VerificationRequirementEditor";

/* --------------------------------- App --------------------------------- */

export default function App() {
  const [projects, setProjects] = useState<Project[]>([]);
  const [profiles, setProfiles] = useState<ModelProfile[]>([]);
  const [activeProjectId, setActiveProjectId] = useState<string>("");
  const [activeProfileId, setActiveProfileId] = useState<string>("");
  const [activeThread, setActiveThread] = useState<Thread | null>(null);
  const [threadsCache, setThreadsCache] = useState<Record<string, Thread[]>>({});
  const [expanded, setExpanded] = useState<Record<string, boolean>>({});
  const [labels, setLabels] = useState<Record<string, string>>({});
  const [queuedPrompt, setQueuedPrompt] = useState<{ text: string; options: TurnSubmissionOptions } | null>(null);
  const [settingsTab, setSettingsTab] = useState<"models" | "project" | null>(null);
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(true);

  const activeProject = projects.find((p) => p.id === activeProjectId) ?? null;
  const activeProfile = profiles.find((p) => p.id === activeProfileId) ?? null;

  const refreshThreads = useCallback(async (projectId: string) => {
    try {
      const ts = await listThreads(projectId);
      setThreadsCache((prev) => ({ ...prev, [projectId]: ts }));
      return ts;
    } catch {
      return [];
    }
  }, []);

  useEffect(() => {
    (async () => {
      try {
        const [ps, pf] = await Promise.all([listProjects(), listModelProfiles()]);
        setProjects(ps);
        setProfiles(pf);
        if (ps.length) setActiveProjectId(ps[0].id);
        if (pf.length) setActiveProfileId(pf[0].id);
        const all = await Promise.all(ps.map((p) => listThreads(p.id).catch(() => [])));
        const cache: Record<string, Thread[]> = {};
        ps.forEach((p, i) => {
          cache[p.id] = all[i];
        });
        setThreadsCache(cache);
        // 会话名：取首条用户消息前 18 字（后端暂无 title）
        const labelEntries = await Promise.all(
          all.flat().map(async (t) => {
            try {
              const ms = await listMessages(t.id, 20);
              const first = ms.find((m) => m.role === "user" && m.content.trim());
              return first
                ? [t.id, first.content.trim().replace(/\s+/g, " ").slice(0, 18)] as const
                : null;
            } catch {
              return null;
            }
          }),
        );
        setLabels(Object.fromEntries(labelEntries.filter((x): x is readonly [string, string] => x !== null)));
        if (ps.length) {
          setExpanded((prev) => ({ ...prev, [ps[0].id]: true }));
        } else {
          setSettingsTab("project");
        }
      } catch (e) {
        setError((e as ApiError).message);
      } finally {
        setLoading(false);
      }
    })();
  }, []);

  const selectProject = (projectId: string) => {
    setActiveProjectId(projectId);
    setActiveThread(null);
    setExpanded((prev) => ({ ...prev, [projectId]: true }));
    if (!threadsCache[projectId]) void refreshThreads(projectId);
  };

  const openThread = (t: Thread) => {
    setActiveProjectId(t.projectId);
    setActiveThread(t);
    setExpanded((prev) => ({ ...prev, [t.projectId]: true }));
  };

  const handleNewChat = () => {
    setActiveThread(null);
    setQueuedPrompt(null);
    setError("");
  };

  const rememberLabel = (threadId: string, text: string) => {
    const label = text.trim().replace(/\s+/g, " ").slice(0, 18);
    if (!label) return;
    // 会话名固定为首条消息，后续追问不覆盖
    setLabels((prev) => (prev[threadId] ? prev : { ...prev, [threadId]: label }));
  };

  // 欢迎态首次发送：先建会话，再让挂载的 Conversation 消费排队中的提问
  const handleFirstSend = async (text: string, options: TurnSubmissionOptions): Promise<boolean> => {
    setError("");
    if (!activeProject) {
      setError("请先选择或创建一个项目");
      setSettingsTab("project");
      return false;
    }
    if (!activeProfile) {
      setError("请先添加并选择模型配置");
      setSettingsTab("models");
      return false;
    }
    try {
      const t = await createThread(activeProject.id, activeProfile.id);
      rememberLabel(t.id, text);
      setQueuedPrompt({ text, options });
      setActiveThread(t);
      void refreshThreads(activeProject.id);
      return true;
    } catch (e) {
      setError((e as ApiError).message);
      return false;
    }
  };

  const handleDeleteThread = async (projectId: string, threadId: string) => {
    const t = (threadsCache[projectId] ?? []).find((x) => x.id === threadId);
    if (t?.activeTurnId) {
      window.alert("该会话还有运行中的任务，请先取消或等待完成后再删除");
      return;
    }
    if (!window.confirm("确定删除这个会话吗？消息、事件、产物和工作区副本都会被清除，且不可恢复。")) return;
    try {
      await deleteThread(projectId, threadId);
      if (activeThread?.id === threadId) setActiveThread(null);
      await refreshThreads(projectId);
    } catch (e) {
      setError((e as ApiError).message);
    }
  };

  const handleDeleteProject = async (projectId: string) => {
    const p = projects.find((x) => x.id === projectId);
    const busy = (threadsCache[projectId] ?? []).some((t) => t.activeTurnId);
    if (busy) {
      window.alert("该项目下还有运行中的任务，请先取消或等待完成后再删除");
      return;
    }
    if (!window.confirm(`确定删除项目「${p?.name ?? ""}」吗？其下全部会话记录、产物和工作区副本都会被清除，且不可恢复。`)) return;
    try {
      await deleteProject(projectId);
      const ps = await listProjects();
      setProjects(ps);
      setThreadsCache((prev) => {
        const next = { ...prev };
        delete next[projectId];
        return next;
      });
      if (activeProjectId === projectId) {
        setActiveThread(null);
        if (ps.length) {
          setActiveProjectId(ps[0].id);
          setExpanded((prev) => ({ ...prev, [ps[0].id]: true }));
          void refreshThreads(ps[0].id);
        } else {
          setActiveProjectId("");
          setSettingsTab("project");
        }
      }
    } catch (e) {
      setError((e as ApiError).message);
    }
  };

  return (
    <div style={S.shell}>
      <Sidebar
        projects={projects}
        threadsCache={threadsCache}
        activeProjectId={activeProjectId}
        activeThreadId={activeThread?.id ?? ""}
        expanded={expanded}
        labels={labels}
        onToggleExpand={(id) => setExpanded((p) => ({ ...p, [id]: !p[id] }))}
        onSelectProject={selectProject}
        onOpenThread={openThread}
        onNewChat={handleNewChat}
        onDeleteThread={handleDeleteThread}
        onDeleteProject={handleDeleteProject}
        onOpenSettings={() => setSettingsTab("models")}
        onAddProject={() => setSettingsTab("project")}
        loading={loading}
      />

      <main style={S.main}>
        {activeProject && activeThread ? (
          <Conversation
            key={activeThread.id}
            project={activeProject}
            profile={
              profiles.find((p) => p.id === activeThread.modelProfileId) ?? activeProfile
            }
            thread={activeThread}
            queuedPrompt={queuedPrompt}
            onPromptConsumed={() => setQueuedPrompt(null)}
            onLabel={(text) => rememberLabel(activeThread.id, text)}
            onError={setError}
          />
        ) : (
          <WelcomePane
            project={activeProject}
            hasProfiles={profiles.length > 0}
            error={error}
            projects={projects}
            profiles={profiles}
            activeProfileId={activeProfileId}
            onProfileChange={setActiveProfileId}
            onProjectChange={selectProject}
            onOpenSettings={(tab) => setSettingsTab(tab)}
            onSend={handleFirstSend}
          />
        )}
      </main>

      {settingsTab && (
        <SettingsModal
          initialTab={settingsTab}
          profiles={profiles}
          onClose={() => setSettingsTab(null)}
          onProfilesChanged={(pf, selectedId) => {
            setProfiles(pf);
            if (selectedId) setActiveProfileId(selectedId);
          }}
          onProjectCreated={(p) => {
            setProjects((prev) => (prev.some((x) => x.id === p.id) ? prev : [...prev, p]));
            setActiveProjectId(p.id);
            setExpanded((prev) => ({ ...prev, [p.id]: true }));
            void refreshThreads(p.id);
          }}
        />
      )}
    </div>
  );
}

/* -------------------------------- Icons -------------------------------- */

const I = {
  folder: (s = 15) => (
    <svg width={s} height={s} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
      <path d="M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z" />
    </svg>
  ),
  chat: (s = 14) => (
    <svg width={s} height={s} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
      <path d="M21 12a8 8 0 0 1-11.6 7.1L4 20l1-4.6A8 8 0 1 1 21 12z" />
    </svg>
  ),
  edit: (s = 16) => (
    <svg width={s} height={s} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
      <path d="M12 20h9" />
      <path d="M16.5 3.5a2.1 2.1 0 0 1 3 3L7 19l-4 1 1-4z" />
    </svg>
  ),
  plus: (s = 15) => (
    <svg width={s} height={s} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round">
      <path d="M12 5v14M5 12h14" />
    </svg>
  ),
  gear: (s = 16) => (
    <svg width={s} height={s} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round">
      <circle cx="12" cy="12" r="3" />
      <path d="M19.4 15a1.6 1.6 0 0 0 .3 1.8l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.6 1.6 0 0 0-2.7 1.1V21a2 2 0 1 1-4 0v-.1A1.6 1.6 0 0 0 7 19.4l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1A1.6 1.6 0 0 0 4.6 14H4.5a2 2 0 1 1 0-4h.1a1.6 1.6 0 0 0 1.1-2.7l-.1-.1a2 2 0 1 1 2.8-2.8l.1.1a1.6 1.6 0 0 0 1.8.3H10a1.6 1.6 0 0 0 1-1.5V3a2 2 0 1 1 4 0v.1a1.6 1.6 0 0 0 2.7 1.1l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1a1.6 1.6 0 0 0-.3 1.8V9a1.6 1.6 0 0 0 1.5 1h.1a2 2 0 1 1 0 4h-.1a1.6 1.6 0 0 0-1.5 1z" />
    </svg>
  ),
  chevron: (s = 12, down = true) => (
    <svg width={s} height={s} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round"
      style={{ transform: down ? "none" : "rotate(-90deg)", transition: "transform .15s" }}>
      <path d="m6 9 6 6 6-6" />
    </svg>
  ),
  close: (s = 13) => (
    <svg width={s} height={s} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round">
      <path d="M18 6 6 18M6 6l12 12" />
    </svg>
  ),
  send: (s = 16) => (
    <svg width={s} height={s} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.4" strokeLinecap="round" strokeLinejoin="round">
      <path d="M12 19V5M5 12l7-7 7 7" />
    </svg>
  ),
  stop: (s = 13) => (
    <svg width={s} height={s} viewBox="0 0 24 24" fill="currentColor">
      <rect x="6" y="6" width="12" height="12" rx="2" />
    </svg>
  ),
  shield: (s = 14) => (
    <svg width={s} height={s} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
      <path d="M12 3l8 3v6c0 5-3.5 8-8 9-4.5-1-8-4-8-9V6z" />
    </svg>
  ),
};

/* ------------------------------- Sidebar ------------------------------- */

function Sidebar(props: {
  projects: Project[];
  threadsCache: Record<string, Thread[]>;
  activeProjectId: string;
  activeThreadId: string;
  expanded: Record<string, boolean>;
  labels: Record<string, string>;
  loading: boolean;
  onToggleExpand: (id: string) => void;
  onSelectProject: (id: string) => void;
  onOpenThread: (t: Thread) => void;
  onNewChat: () => void;
  onDeleteThread: (projectId: string, threadId: string) => void;
  onDeleteProject: (projectId: string) => void;
  onOpenSettings: () => void;
  onAddProject: () => void;
}) {
  return (
    <aside style={S.sidebar}>
      <div style={S.sideHeader}>
        <span style={S.brand}>DSH</span>
        {I.chevron(13)}
        <span style={{ flex: 1 }} />
        <button style={S.iconBtn} title="设置" onClick={props.onOpenSettings}>
          {I.gear(16)}
        </button>
      </div>

      <button style={S.newChatBtn} onClick={props.onNewChat}>
        <span style={{ display: "inline-flex", alignItems: "center", gap: 9 }}>
          {I.edit(15)} <span style={{ fontSize: 14.5 }}>新对话</span>
        </span>
        {I.plus(14)}
      </button>

      <div style={S.sectionRow}>
        <span style={S.sectionLabel}>项目</span>
        <button style={S.iconBtn} title="添加项目" onClick={props.onAddProject}>
          {I.plus(13)}
        </button>
      </div>

      <div style={S.sideList}>
        {props.loading && <div style={S.sideHint}>加载中…</div>}
        {!props.loading && props.projects.length === 0 && (
          <div style={S.sideHint}>还没有项目，点上方 + 创建</div>
        )}
        {props.projects.map((p) => {
          const isOpen = props.expanded[p.id];
          const isActiveProject = p.id === props.activeProjectId;
          const threads = props.threadsCache[p.id] ?? [];
          return (
            <div key={p.id}>
              <HoverRow
                style={{
                  ...S.projectRow,
                  ...(isActiveProject ? S.rowActive : {}),
                }}
                onClick={() => {
                  props.onSelectProject(p.id);
                }}
              >
                <span
                  style={{ display: "inline-flex", padding: "2px 1px" }}
                  onClick={(e) => {
                    e.stopPropagation();
                    props.onToggleExpand(p.id);
                  }}
                >
                  {I.chevron(11, !!isOpen)}
                </span>
                <span style={{ color: "#5b6270", display: "inline-flex" }}>{I.folder(15)}</span>
                <span style={S.rowText}>{p.name}</span>
                <span
                  role="button"
                  aria-label="删除项目"
                  className="row-del"
                  style={S.rowDel}
                  onClick={(e) => {
                    e.stopPropagation();
                    void props.onDeleteProject(p.id);
                  }}
                >
                  {I.close(12)}
                </span>
              </HoverRow>

              {isOpen && (
                <div style={S.threadGroup}>
                  {threads.length === 0 && <div style={S.sideHint}>（暂无会话）</div>}
                  {threads
                    .slice()
                    .sort((a, b) => (b.updatedAt ?? "").localeCompare(a.updatedAt ?? ""))
                    .map((t) => {
                      const isActiveThread = t.id === props.activeThreadId;
                      return (
                        <HoverRow
                          key={t.id}
                          style={{
                            ...S.threadRow,
                            ...(isActiveThread ? S.threadRowActive : {}),
                          }}
                          onClick={() => props.onOpenThread(t)}
                        >
                          <span style={{ color: "#8a909c", display: "inline-flex" }}>{I.chat(13)}</span>
                          <span style={S.rowText}>{props.labels[t.id] || t.title || "新对话"}</span>
                          <span
                            role="button"
                            aria-label="删除会话"
                            className="row-del"
                            style={S.rowDel}
                            onClick={(e) => {
                              e.stopPropagation();
                              void props.onDeleteThread(p.id, t.id);
                            }}
                          >
                            {I.close(11)}
                          </span>
                        </HoverRow>
                      );
                    })}
                </div>
              )}
            </div>
          );
        })}
      </div>

      <div style={S.sideFooter}>
        <span style={{ width: 7, height: 7, borderRadius: 7, background: "#22c55e", display: "inline-block" }} />
        <span style={{ fontSize: 12, color: "#8a909c" }}>本地工作区 · 沙箱隔离</span>
      </div>
    </aside>
  );
}

/** 悬停才显示删除小按钮的行。 */
function HoverRow({ style, onClick, children }: {
  style?: CSSProperties;
  onClick?: () => void;
  children: ReactNode;
}) {
  const [hover, setHover] = useState(false);
  return (
    <div
      role="button"
      tabIndex={0}
      style={{ ...style, position: "relative" }}
      onMouseEnter={() => setHover(true)}
      onMouseLeave={() => setHover(false)}
      onClick={onClick}
      onKeyDown={(e) => e.key === "Enter" && onClick?.()}
      data-hover={hover}
    >
      {children}
    </div>
  );
}

/* ----------------------------- Welcome pane ----------------------------- */

function WelcomePane(props: {
  project: Project | null;
  hasProfiles: boolean;
  error: string;
  projects: Project[];
  profiles: ModelProfile[];
  activeProfileId: string;
  onProfileChange: (id: string) => void;
  onProjectChange: (id: string) => void;
  onOpenSettings: (tab: "models" | "project") => void;
  onSend: (text: string, options: TurnSubmissionOptions) => Promise<boolean>;
}) {
  return (
    <div style={S.pane}>
      <div style={S.welcomeWrap}>
        <CloudLogo />
        <h2 style={S.welcomeTitle}>
          我们应该在
          <span style={S.welcomeProject}>{props.project ? props.project.name : "哪个项目"}</span>
          中做些什么？
        </h2>
      </div>
      <div style={S.composerDock}>
        <Composer
          project={props.project}
          projects={props.projects}
          profiles={props.profiles}
          profileId={props.activeProfileId}
          running={false}
          statusText=""
          onProfileChange={props.onProfileChange}
          onProjectChange={props.onProjectChange}
          onOpenSettings={props.onOpenSettings}
          onSend={props.onSend}
          onCancel={() => {}}
        />
        {props.error && <div style={S.composerError}>{props.error}</div>}
      </div>
    </div>
  );
}

function CloudLogo() {
  return (
    <svg width="96" height="96" viewBox="0 0 96 96" fill="none" style={{ color: "#b6bbc4", marginBottom: 18 }}>
      <path
        d="M30 66h36a14 14 0 0 0 2-27.9A20 20 0 0 0 30.6 31 16 16 0 0 0 30 66z"
        stroke="currentColor"
        strokeWidth="2.4"
        strokeLinejoin="round"
      />
      <path d="M40 52l6 6 12-14" stroke="currentColor" strokeWidth="2.6" strokeLinecap="round" strokeLinejoin="round" />
    </svg>
  );
}

/* ------------------------------ Conversation ----------------------------- */

function Conversation(props: {
  project: Project;
  profile: ModelProfile | null;
  thread: Thread;
  queuedPrompt: { text: string; options: TurnSubmissionOptions } | null;
  onPromptConsumed: () => void;
  onLabel: (text: string) => void;
  onError: (msg: string) => void;
}) {
  const { project, profile, thread } = props;
  // A queued welcome-page request may fail after the new Conversation mounts.
  const [input, setInput] = useState(props.queuedPrompt?.text ?? "");
  const [initialRequirement, setInitialRequirement] = useState(props.queuedPrompt?.options.verification);
  const submissionRef = useRef(false);
  const [events, setEvents] = useState<EventEnvelope[]>([]);
  const [messages, setMessages] = useState<MessageItem[]>([]);
  const [runId, setRunId] = useState("");
  const [running, setRunning] = useState(false);
  const [currentTurnId, setCurrentTurnId] = useState<string | null>(thread.activeTurnId);
  const [approvals, setApprovals] = useState<Approval[]>([]);
  const [diff, setDiff] = useState<Diff | null>(null);
  const [verifications, setVerifications] = useState<Verification[]>([]);
  // MA-10：计划任务快照、根/子 Run 预算视图
  const [plans, setPlans] = useState<PlanView[]>([]);
  const [rootRun, setRootRun] = useState<Run | null>(null);
  const [effectivePlan, setEffectivePlan] = useState<VerificationPlanView | null>(null);
  const [effectivePlanError, setEffectivePlanError] = useState("");
  const [childRuns, setChildRuns] = useState<Run[]>([]);
  const [error, setError] = useState("");
  const wsRef = useRef<EventStreamClient | null>(null);
  const seqRef = useRef("0");
  const runIdRef = useRef("");
  const scrollRef = useRef<HTMLDivElement>(null);
  const consumedRef = useRef(false);

  const fail = (e: unknown) => {
    const msg = (e as ApiError).message ?? String(e);
    setError(msg);
    props.onError(msg);
  };

  const loadDiffAndVerify = useCallback(async (rid: string) => {
    try {
      const [d, v] = await Promise.all([getDiff(rid), listVerifications(rid)]);
      setDiff(d);
      setVerifications(v.items);
    } catch {
      /* 非关键 */
    }
  }, []);

  // MA-10：计划快照 + 根/子 Run 预算（编排关闭或无计划时 404/空列表均非关键）
  const refreshRunViews = useCallback(async (rid: string) => {
    if (!rid) return;
    try {
      const [ps, root, children, acceptance] = await Promise.all([
        listPlans(rid),
        getRun(rid),
        listChildRuns(rid),
        getRunVerificationPlan(rid).then(plan => { setEffectivePlanError(""); return plan; })
          .catch(e => { setEffectivePlanError((e as ApiError).message ?? "有效验收要求暂时无法读取"); return null; }),
      ]);
      setPlans(ps);
      setRootRun(root);
      setEffectivePlan(acceptance);
      setChildRuns(children);
    } catch {
      /* 非关键 */
    }
  }, []);

  const appendEvent = useCallback(
    (e: EventEnvelope) => {
      setEvents((prev) => (prev.some((x) => x.eventId === e.eventId) ? prev : [...prev, e]));
      seqRef.current = newerSequence(seqRef.current, e.sequence);
      // MA-10：编排任务事件只折叠当前根 Run（防跨 Turn 污染）
      if (["task.dispatched", "task.state.changed", "task.result.ready"].includes(e.type)) {
        const rid = e.runId ?? runIdRef.current;
        setPlans((prev) => foldPlans(prev, e, rid));
        if (e.type === "task.state.changed"
          && ["SUCCEEDED", "FAILED", "CANCELLED", "BLOCKED"].includes(String(e.payload.to))) {
          void refreshRunViews(rid);
        }
      }
      // 计划修订/停车/集成落定：以快照为准重建任务面板与预算
      if (["plan.updated", "run.waiting_tasks", "integration.completed", "integration.failed"]
        .includes(e.type)) {
        void refreshRunViews(e.runId ?? runIdRef.current);
      }
      if (e.type === "run.completed" || e.type === "run.failed" || e.type === "run.cancelled") {
        setRunning(false);
        const rid = e.runId ?? "";
        if (rid) {
          void loadDiffAndVerify(rid);
          void refreshRunViews(rid);
        }
        void listMessages(thread.id).then(setMessages).catch(() => {});
      }
      if (e.type === "run.state_changed" && e.payload.to === "CANCELLED") {
        setRunning(false);
        setApprovals([]);
      }
    },
    [loadDiffAndVerify, refreshRunViews, thread.id],
  );

  const restLoad = useCallback(async () => {
    try {
      const history = await listAllEvents(thread.id);
      if (history.length) {
        setEvents((live) => {
          const byId = new Map<string, EventEnvelope>();
          for (const event of history) byId.set(event.eventId, event);
          for (const event of live) byId.set(event.eventId, event);
          return [...byId.values()].sort((a, b) =>
            BigInt(a.sequence) < BigInt(b.sequence) ? -1 : BigInt(a.sequence) > BigInt(b.sequence) ? 1 : 0);
        });
        seqRef.current = newerSequence(seqRef.current, history[history.length - 1].sequence);
        wsRef.current?.setLastSequence(seqRef.current);
        const lastRunId = [...history].reverse().find((e) => e.runId)?.runId ?? "";
        const terminal = history.some((e) => e.runId === lastRunId &&
          ["run.completed", "run.failed", "run.cancelled"].includes(e.type));
        if (lastRunId) {
          setRunId(lastRunId);
          runIdRef.current = lastRunId;
          setCurrentTurnId([...history].reverse().find((e) => e.runId === lastRunId)?.turnId ?? null);
          setRunning(!terminal);
          void refreshRunViews(lastRunId);
          if (terminal) void loadDiffAndVerify(lastRunId);
        }
      }
    } catch (e) {
      console.warn("rest load error", e);
    }
  }, [thread.id, loadDiffAndVerify, refreshRunViews]);

  // 历史消息（用户/助手文本）
  useEffect(() => {
    void listMessages(thread.id)
      .then((ms) => setMessages(ms.filter((m) => m.role === "user" || m.role === "assistant")))
      .catch(() => {});
  }, [thread.id]);

  // WebSocket
  useEffect(() => {
    const client = new EventStreamClient(
      thread.id,
      seqRef.current,
      appendEvent,
      () => {
        setEvents([]);
        seqRef.current = "0";
        void restLoad();
      },
    );
    wsRef.current = client;
    client.connect();
    return () => {
      client.close();
      wsRef.current = null;
    };
  }, [thread.id, appendEvent, restLoad]);

  // 历史会话恢复
  useEffect(() => {
    // Terminal conversations also need their last Run and evidence restored.
    void restLoad();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // 审批轮询
  useEffect(() => {
    const asks = events.filter((e) => e.type === "approval.requested");
    if (!asks.length) return;
    const turnId = asks[asks.length - 1].turnId ?? currentTurnId;
    if (!turnId) return;
    void listApprovals(turnId)
      .then((a) => setApprovals(a.filter((x) => x.status === "PENDING")))
      .catch(() => {});
  }, [events, currentTurnId]);

  // 排队中的首次提问（欢迎态刚建完会话）；StrictMode 双挂载下只消费一次
  useEffect(() => {
    if (consumedRef.current) return;
    if (props.queuedPrompt) {
      consumedRef.current = true;
      const queued = props.queuedPrompt;
      props.onPromptConsumed();
      void doSubmit(queued.text, queued.options);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // 自动滚底
  useEffect(() => {
    const el = scrollRef.current;
    if (el) el.scrollTop = el.scrollHeight;
  }, [events, messages, approvals, verifications, diff]);

  const doSubmit = async (raw?: string, options: TurnSubmissionOptions = {}): Promise<boolean> => {
    const text = (raw ?? input).trim();
    if (!text || running || submissionRef.current) return false;
    submissionRef.current = true;
    setError("");
    try {
      const accepted = await submitTurn(thread.id, text, options);
      setDiff(null);
      setVerifications([]);
      setApprovals([]);
      setPlans([]);
      setRootRun(null);
      setEffectivePlan(null);
      setEffectivePlanError("");
      setChildRuns([]);
      setRunId(accepted.rootRunId);
      runIdRef.current = accepted.rootRunId;
      setCurrentTurnId(accepted.turnId);
      setRunning(true);
      setInput("");
      setInitialRequirement(undefined);
      props.onLabel(text);
      void refreshRunViews(accepted.rootRunId);
      void listMessages(thread.id)
        .then((ms) => setMessages(ms.filter((m) => m.role === "user" || m.role === "assistant")))
        .catch(() => {});
      return true;
    } catch (e) {
      fail(e);
      return false;
    } finally { submissionRef.current = false; }
  };

  const doAppend = async (text: string): Promise<boolean> => {
    if (!currentTurnId || !running) return false;
    setError("");
    try {
      await appendTurnInput(currentTurnId, text);
      void listMessages(thread.id).then(setMessages).catch(() => {});
      if (runId) { void refreshRunViews(runId); void loadDiffAndVerify(runId); }
      return true;
    } catch (e) { fail(e); return false; }
  };

  const handleCancel = async () => {
    if (!runId) return;
    try {
      const run = await cancelRun(runId);
      setApprovals([]);
      if (["CANCELLED", "COMPLETED", "FAILED"].includes(run.state)) setRunning(false);
    } catch (e) {
      fail(e);
    }
  };

  const handleApproval = async (approval: Approval, decision: "APPROVE" | "DENY") => {
    try {
      await decideApproval(approval, decision);
      setApprovals((prev) => prev.filter((a) => a.id !== approval.id));
    } catch (e) {
      fail(e);
    }
  };

  const items = useMemo(
    () => buildTimeline(messages, events),
    [messages, events],
  );
  const statusText = running ? runStatusText(events) : "";
  const showEmpty = items.length === 0;

  return (
    <div style={S.pane}>
      {effectivePlanError && <div role="alert" style={{ padding: "8px 24px", color: "#b91c1c" }}>验收要求读取失败：{effectivePlanError}</div>}
      {effectivePlan && <details style={{ padding: "8px 24px", borderBottom: "1px solid #e5e7eb" }}><summary>查看本次运行的有效验收要求</summary><VerificationPlanSummary plan={effectivePlan} /></details>}
      <div ref={scrollRef} style={S.timeline}>
        <div style={S.timelineInner}>
          {showEmpty && (
            <div style={S.chatEmpty}>
              <CloudLogo />
              <div style={{ fontSize: 17, color: "#8a909c" }}>
                {project.name} · {profile?.name ?? "模型"}
              </div>
            </div>
          )}
          {items.map((it) => (
            <TimelineRow key={it.key} item={it} />
          ))}
          {diff && diff.files.length > 0 && <DiffCard diff={diff} />}
          {plans.length > 0 && (
            <PlanPanel plans={plans} rootRun={rootRun} childRuns={childRuns} />
          )}
          {runId && (
            <EvidenceCard runId={runId} events={events} verifications={verifications} />
          )}
          {running && (
            <div style={S.thinkingRow}>
              <span style={S.dots}>
                <i className="tdot" /> <i className="tdot" /> <i className="tdot" />
              </span>
              <span style={{ color: "#8a909c", fontSize: 13 }}>{statusText || "思考中…"}</span>
            </div>
          )}
        </div>
      </div>

      <div style={S.composerDock}>
        {approvals.length > 0 && (
          <div style={S.approvalStack}>
            {approvals.map((a) => (
              <div key={a.id} style={S.approvalCard}>
                <div style={S.approvalHead}>
                  <span style={{ color: "#b7791f", display: "inline-flex" }}>{I.shield(15)}</span>
                  <strong style={{ fontSize: 14 }}>需要批准：{a.toolName}</strong>
                  <span style={S.approvalRisk}>{a.risk}</span>
                </div>
                <pre style={S.approvalArgs}>{a.argumentsSummary}</pre>
                {a.reason && <div style={S.approvalReason}>{a.reason}</div>}
                <div style={S.approvalActions}>
                  <button style={S.approveBtn} onClick={() => void handleApproval(a, "APPROVE")}>
                    批准执行
                  </button>
                  <button style={S.denyBtn} onClick={() => void handleApproval(a, "DENY")}>
                    拒绝
                  </button>
                </div>
              </div>
            ))}
          </div>
        )}
        <Composer
          key={initialRequirement ? "queued-registration" : "current"}
          project={project}
          projects={[project]}
          profiles={profile ? [profile] : []}
          profileId={profile?.id ?? ""}
          running={running}
          statusText={statusText}
          onProfileChange={() => {}}
          onProjectChange={() => {}}
          onOpenSettings={() => {}}
          lockProject
          value={input}
          onValue={setInput}
          onSend={(text, options) => doSubmit(text, options)}
          onAppend={currentTurnId ? doAppend : undefined}
          initialRequirement={initialRequirement}
          onCancel={() => void handleCancel()}
        />
        {error && <div style={S.composerError}>{error}</div>}
      </div>
    </div>
  );
}

/* ---------------------------- Timeline building ---------------------------- */

type TimelineItem =
  | { key: string; kind: "user" | "assistant"; time: string; text: string }
  | {
      key: string;
      kind: "tool";
      time: string;
      name: string;
      args?: string;
      status?: string;
      output?: string;
      artifactIds?: string[];
      truncated?: boolean;
      exitCode?: number | null;
    }
  | { key: string; kind: "approval"; time: string; toolName?: string; status?: string }
  | { key: string; kind: "verifying"; time: string }
  | { key: string; kind: "terminal"; time: string; outcome: "completed" | "failed" | "cancelled"; detail: string }
  | { key: string; kind: "verification"; time: string; status: string; stale: boolean };

function buildTimeline(messages: MessageItem[], events: EventEnvelope[]): TimelineItem[] {
  const items: TimelineItem[] = [];
  const toolIndex = new Map<string, number>();
  const approvalIndex = new Map<string, number>();
  const liveAssistantTexts = new Set<string>();

  for (const e of events) {
    const p = e.payload as Record<string, unknown>;
    const time = e.timestamp;
    switch (e.type) {
      case "tool.started": {
        const id = String(p.callId ?? e.eventId);
        toolIndex.set(id, items.length);
        items.push({
          key: e.eventId,
          kind: "tool",
          time,
          name: String(p.name ?? "tool"),
          args: (p.argumentsSummary ?? "").toString(),
        });
        break;
      }
      case "tool.completed": {
        const id = String(p.callId ?? "");
        const result = (p.result ?? {}) as Record<string, unknown>;
        const blocks = Array.isArray(result.content)
          ? (result.content as Array<Record<string, unknown>>)
                .filter((b) => b.type === "text")
                .map((b) => String(b.text ?? ""))
                .join("\n")
          : "";
        const patch: TimelineItem = {
          key: e.eventId,
          kind: "tool",
          time,
          name: String(p.name ?? "tool"),
          args: undefined,
          status: (result.status ?? "").toString(),
          output: blocks || ((result.summary ?? "") as string),
          artifactIds: Array.isArray(result.artifactIds)
            ? result.artifactIds.filter((id): id is string => typeof id === "string" && /^art_[A-Za-z0-9_-]{1,124}$/.test(id)).slice(0, 16)
            : [],
          truncated: Boolean(result.truncated),
          exitCode: result.exitCode == null ? null : Number(result.exitCode),
        };
        const idx = toolIndex.get(id);
        if (idx !== undefined) {
          const started = items[idx];
          if (started.kind === "tool") {
            items[idx] = { ...started, ...patch, args: started.args, key: started.key };
          }
        } else {
          toolIndex.set(id, items.length);
          items.push(patch);
        }
        break;
      }
      case "approval.requested": {
        const id = String(p.approvalId ?? e.eventId);
        approvalIndex.set(id, items.length);
        items.push({ key: e.eventId, kind: "approval", time });
        break;
      }
      case "approval.resolved": {
        const id = String(p.approvalId ?? "");
        const status = String(p.status ?? "");
        const idx = approvalIndex.get(id);
        if (idx !== undefined) {
          const cur = items[idx];
          if (cur.kind === "approval") items[idx] = { ...cur, status };
        }
        break;
      }
      case "run.state_changed":
        if (p.to === "VERIFYING") {
          items.push({ key: e.eventId, kind: "verifying", time });
        }
        break;
      case "run.completed":
        items.push({
          key: e.eventId,
          kind: "terminal",
          time,
          outcome: "completed",
          detail: `已完成（验证：${p.verificationStatus ?? "—"}）`,
        });
        break;
      case "run.failed":
        items.push({
          key: e.eventId,
          kind: "terminal",
          time,
          outcome: "failed",
          detail: `失败：${p.reason ?? ""} ${p.message ?? ""}`.trim(),
        });
        break;
      case "run.cancelled":
        items.push({ key: e.eventId, kind: "terminal", time, outcome: "cancelled", detail: "已取消" });
        break;
      case "verification.completed":
        items.push({
          key: e.eventId,
          kind: "verification",
          time,
          status: String(p.status ?? ""),
          stale: Boolean(p.stale),
        });
        break;
      case "assistant.message.completed": {
        const text = String(p.content ?? "").trim();
        if (text) {
          liveAssistantTexts.add(text);
          items.push({ key: e.eventId, kind: "assistant", time, text });
        }
        break;
      }
      default:
        break;
    }
  }

  // 历史消息补齐（事件流已有的同文本助手消息不重复展示）
  for (const m of messages) {
    const text = m.content.trim();
    if (!text) continue;
    if (m.role === "user") {
      items.push({ key: m.id, kind: "user", time: m.createdAt, text });
    } else if (m.role === "assistant" && !liveAssistantTexts.has(text)) {
      items.push({ key: m.id, kind: "assistant", time: m.createdAt, text });
    }
  }

  items.sort((a, b) => a.time.localeCompare(b.time));
  return items;
}

function runStatusText(events: EventEnvelope[]): string {
  let s = "思考中…";
  for (const e of events) {
    if (e.type === "run.state_changed") {
      const to = String(e.payload.to ?? "");
      if (to === "WAITING_APPROVAL") s = "等待审批…";
      else if (to === "WAITING_INPUT") s = "等待补充需求…";
      else if (to === "VERIFYING") s = "正在验证…";
      else if (to === "CANCELLING") s = "正在取消…";
      else if (to === "RUNNING") s = "思考中…";
    }
  }
  return s;
}

function TimelineRow({ item }: { item: TimelineItem }) {
  const time = new Date(item.time).toLocaleTimeString();
  switch (item.kind) {
    case "user":
      return (
        <div style={S.bubbleRow}>
          <div style={S.userBubble}>
            <div style={S.bubbleText}>{item.text}</div>
          </div>
        </div>
      );
    case "assistant":
      return (
        <div style={S.assistantBlock}>
          <div style={S.bubbleText}>{item.text}</div>
          <div style={S.itemTime}>{time}</div>
        </div>
      );
    case "tool":
      return <ToolRow item={item} time={time} />;
    case "approval":
      return (
        <div style={S.inlineRow}>
          <span style={{ color: item.status ? "#8a909c" : "#b7791f", display: "inline-flex" }}>{I.shield(13)}</span>
          <span style={{ fontSize: 13, color: item.status ? "#8a909c" : "#4b5563" }}>
            {item.toolName ? `${item.toolName} 审批` : "工具审批"}
            {item.status ? ` · ${approvalStatusText(item.status)}` : " · 等待处理"}
          </span>
        </div>
      );
    case "verifying":
      return (
        <div style={S.inlineRow}>
          <span style={{ fontSize: 13, color: "#2563eb" }}>正在运行验证…</span>
        </div>
      );
    case "terminal":
      return (
        <div style={{ ...S.inlineRow, ...(item.outcome === "failed" ? { color: "#dc2626" } : item.outcome === "cancelled" ? { color: "#8a909c" } : { color: "#16a34a" }) }}>
          <span style={{ fontSize: 13.5, fontWeight: 600 }}>
            {item.outcome === "completed" ? "✓ " : item.outcome === "failed" ? "✗ " : "⊘ "}
            {item.detail}
          </span>
        </div>
      );
    case "verification":
      return (
        <div style={S.inlineRow}>
          <span
            style={{
              fontSize: 12.5,
              fontWeight: 600,
              color: "white",
              background: item.status === "PASSED" ? "#16a34a" : "#dc2626",
              borderRadius: 6,
              padding: "2px 8px",
            }}
          >
            验证 {item.status}
            {item.stale ? "（已过期）" : ""}
          </span>
        </div>
      );
  }
}

function approvalStatusText(s: string): string {
  if (s === "APPROVED") return "已批准";
  if (s === "DENIED") return "已拒绝";
  if (s === "EXPIRED") return "已过期/取消";
  return s;
}

function ToolRow({ item, time }: { item: Extract<TimelineItem, { kind: "tool" }>; time: string }) {
  const [open, setOpen] = useState(false);
  const failed = item.status && item.status !== "SUCCEEDED" && item.status !== "DENIED";
  const denied = item.status === "DENIED";
  return (
    <div style={S.toolCard}>
      <div role="button" tabIndex={0} style={S.toolHead} onClick={() => setOpen((v) => !v)}
        onKeyDown={(e) => e.key === "Enter" && setOpen((v) => !v)}>
        <span style={{
          width: 8, height: 8, borderRadius: 8, flex: "0 0 auto",
          background: item.status ? (denied ? "#f59e0b" : failed ? "#ef4444" : "#22c55e") : "#9ca3af",
        }} />
        <span style={{ fontSize: 13, fontWeight: 500, color: "#374151" }}>{item.name}</span>
        {item.status && (
          <span style={{
            fontSize: 11, color: denied ? "#b45309" : failed ? "#dc2626" : "#15803d",
            background: denied ? "#fef3c7" : failed ? "#fee2e2" : "#dcfce7",
            borderRadius: 5, padding: "1px 6px",
          }}>
            {item.status}
            {item.exitCode != null ? ` · exit ${item.exitCode}` : ""}
          </span>
        )}
        <span style={{ flex: 1 }} />
        <span style={{ fontSize: 11, color: "#9ca3af" }}>{time}</span>
        {I.chevron(11, open)}
      </div>
      {open && (
        <div style={S.toolBody}>
          {item.args && (
            <>
              <div style={S.toolLabel}>参数</div>
              <pre style={S.toolPre}>{item.args}</pre>
            </>
          )}
          {item.output && (
            <>
              <div style={S.toolLabel}>输出</div>
              <pre style={S.toolPre}>{item.output}</pre>
            </>
          )}
          {!!item.artifactIds?.length && (
            <div style={{ fontSize: 12, marginTop: 8 }}>
              {item.artifactIds.map((id) => (
                <a key={id} href={artifactContentUrl(id)} target="_blank" rel="noreferrer" style={{ display: "block" }}>
                  {item.name === "run_probe" ? "诊断探针源码" : "工具产物"} {shortId(id)}
                </a>
              ))}
            </div>
          )}
          {item.truncated && <div style={{ color: "#b45309", fontSize: 12 }}>输出过长已截断</div>}
        </div>
      )}
    </div>
  );
}

/** 运行结束后的工作区文件变更（折叠卡，默认收起）。 */
function DiffCard({ diff }: { diff: Diff }) {
  const [open, setOpen] = useState(false);
  const added = diff.files.filter((f) => f.status === "ADDED").length;
  const modified = diff.files.filter((f) => f.status === "MODIFIED" || f.status === "RENAMED").length;
  const deleted = diff.files.filter((f) => f.status === "DELETED").length;
  return (
    <div style={S.toolCard}>
      <div
        role="button"
        tabIndex={0}
        style={S.toolHead}
        onClick={() => setOpen((v) => !v)}
        onKeyDown={(e) => e.key === "Enter" && setOpen((v) => !v)}
      >
        <span style={{ width: 8, height: 8, borderRadius: 8, flex: "0 0 auto", background: "#2563eb" }} />
        <span style={{ fontSize: 13, fontWeight: 500, color: "#374151" }}>
          {diff.files.length} 个文件变更
        </span>
        {added > 0 && <span style={S.diffStatAdded}>+{added}</span>}
        {modified > 0 && <span style={S.diffStatModified}>~{modified}</span>}
        {deleted > 0 && <span style={S.diffStatDeleted}>-{deleted}</span>}
        {diff.truncated && <span style={{ fontSize: 11, color: "#b45309" }}>（补丁已截断）</span>}
        <span style={{ flex: 1 }} />
        {I.chevron(11, open)}
      </div>
      {open && (
        <div style={S.toolBody}>
          {diff.files.map((f) => (
            <div key={f.path + f.status} style={S.diffFileRow}>
              <span style={diffStatusStyle(f.status)}>{diffStatusText(f.status)}</span>
              <span style={{ fontSize: 12.5, color: "#374151", wordBreak: "break-all" }}>
                {f.path}
                {f.status === "RENAMED" && f.oldPath ? ` ← ${f.oldPath}` : ""}
                {f.binary ? "（二进制）" : ""}
              </span>
            </div>
          ))}
          {diff.patch && (
            <>
              <div style={S.toolLabel}>补丁</div>
              <pre style={S.toolPre}>{diff.patch}</pre>
            </>
          )}
        </div>
      )}
    </div>
  );
}

function diffStatusText(s: string): string {
  if (s === "ADDED") return "新增";
  if (s === "MODIFIED") return "修改";
  if (s === "DELETED") return "删除";
  if (s === "RENAMED") return "重命名";
  return s;
}

function diffStatusStyle(s: string): CSSProperties {
  const base: CSSProperties = {
    fontSize: 11, fontWeight: 600, borderRadius: 5, padding: "1px 6px", flex: "0 0 auto",
  };
  if (s === "ADDED") return { ...base, color: "#15803d", background: "#dcfce7" };
  if (s === "DELETED") return { ...base, color: "#b91c1c", background: "#fee2e2" };
  return { ...base, color: "#1d4ed8", background: "#dbeafe" };
}

/* -------------------------- MA-10 计划任务与预算面板 -------------------------- */

const TASK_STATE_TEXT: Record<string, string> = {
  DRAFT: "草稿",
  PENDING: "等待依赖",
  READY: "就绪",
  RUNNING: "执行中",
  VERIFYING: "验收中",
  WAITING_APPROVAL: "等待审批",
  BLOCKED: "阻塞",
  FAILED: "失败",
  CANCELLING: "已请求取消",
  CANCELLED: "已停止",
  SUCCEEDED: "已完成",
};

const TASK_REASON_TEXT: Record<string, string> = {
  DISPATCH_REQUESTED: "已派发",
  DEPENDENCIES_SATISFIED: "依赖已满足",
  CHILD_COMPLETED_ENTER_VERIFYING: "子 Agent 已完成，进入验收",
  VERIFICATION_PASSED: "验收通过",
  CHILD_FAILED: "子 Agent 执行失败",
  CHILD_CANCELLED: "子 Agent 已停止",
  EVIDENCE_MISSING: "自述成功但无产物证据",
  VERIFICATION_FAILED: "验收未通过",
  DEPENDENCY_TERMINAL_UNSUCCESSFUL: "上游终态未成功",
  RETRY_LIMIT_EXCEEDED: "重试超限",
  CANCEL_REQUESTED: "取消已登记",
  COMPLETED_AFTER_CANCEL_REQUEST: "取消后迟到的完成",
  UPSTREAM_CANCELLED: "上游已取消",
  ROOT_TERMINAL_CANCELLED: "根 Run 终态级联取消",
};

function taskStateStyle(state: string): CSSProperties {
  const base: CSSProperties = {
    fontSize: 11, fontWeight: 600, borderRadius: 5, padding: "1px 7px", flex: "0 0 auto",
  };
  if (state === "SUCCEEDED") return { ...base, color: "#15803d", background: "#dcfce7" };
  if (state === "FAILED" || state === "BLOCKED")
    return { ...base, color: "#b91c1c", background: "#fee2e2" };
  if (state === "CANCELLING") return { ...base, color: "#b45309", background: "#fef3c7" };
  if (state === "CANCELLED") return { ...base, color: "#6b7280", background: "#eef0f2" };
  if (state === "RUNNING" || state === "VERIFYING")
    return { ...base, color: "#1d4ed8", background: "#dbeafe" };
  return { ...base, color: "#4b5563", background: "#eef0f2" };
}

function shortId(s: string | null | undefined, head = 10): string {
  if (!s) return "—";
  return s.length <= head + 4 ? s : `${s.slice(0, head)}…`;
}

function BudgetLine({ label, budget }: { label: string; budget: NonNullable<Run["budget"]> }) {
  const settled = budget.settledTokens ?? 0;
  const pct = budget.maxCumulativeTokens > 0
    ? Math.min(100, Math.round(((settled + budget.reservedTokens) / budget.maxCumulativeTokens) * 100))
    : 0;
  return (
    <div style={{ display: "flex", alignItems: "center", gap: 8, fontSize: 12, padding: "2px 0" }}>
      <span style={{ color: "#6b7280", flex: "0 0 84px" }}>{label}</span>
      <span style={{ color: "#374151", fontFamily: "ui-monospace, Menlo, Consolas, monospace" }}>
        结算 {settled} / 预留 {budget.reservedTokens} / 上限 {budget.maxCumulativeTokens}
      </span>
      <span style={{ flex: 1, height: 5, borderRadius: 4, background: "#eef0f2", overflow: "hidden" }}>
        <span style={{ display: "block", width: `${pct}%`, height: "100%", background: pct > 90 ? "#dc2626" : "#2563eb" }} />
      </span>
    </div>
  );
}

/** MA-10：计划任务面板——依赖/权威状态/取消语义/失败原因/产物与验收证据/预算口径。 */
function PlanPanel({ plans, rootRun, childRuns }: {
  plans: PlanView[];
  rootRun: Run | null;
  childRuns: Run[];
}) {
  const taskCount = plans.reduce((n, p) => n + p.tasks.length, 0);
  return (
    <div style={S.toolCard}>
      <div style={S.toolHead}>
        <span style={{ width: 8, height: 8, borderRadius: 8, flex: "0 0 auto", background: "#7c3aed" }} />
        <span style={{ fontSize: 13, fontWeight: 500, color: "#374151" }}>
          计划任务（{plans.length} 个计划 / {taskCount} 个任务）
        </span>
      </div>
      <div style={S.toolBody}>
        {rootRun && <TaskAcceptanceModePanel key={rootRun.id} rootRunId={rootRun.id}/>}
        {plans.map((plan) => (
          <div key={plan.planId} style={{ padding: "6px 0 10px" }}>
            <div style={{ fontSize: 12.5, fontWeight: 600, color: "#374151", display: "flex", gap: 8, alignItems: "center" }}>
              <span>修订 #{plan.revision}</span>
              {plan.goal && <span style={{ fontWeight: 400, color: "#6b7280" }}>{plan.goal}</span>}
            </div>
            {plan.tasks.map((t) => {
              const reason = t.reasonCode ? (TASK_REASON_TEXT[t.reasonCode] ?? t.reasonCode) : "";
              return (
                <div key={`${t.taskId}@${t.taskVersion}`}
                  style={{ borderTop: "1px solid #eef0f2", padding: "7px 0" }}>
                  <div style={{ display: "flex", alignItems: "center", gap: 8 }}>
                    <span style={taskStateStyle(t.state)}>
                      {TASK_STATE_TEXT[t.state] ?? t.state}
                    </span>
                    <span style={{ fontSize: 12.5, fontWeight: 600, color: "#374151" }}>
                      {t.taskId}
                      <span style={{ fontWeight: 400, color: "#9ca3af" }}> v{t.taskVersion}</span>
                    </span>
                    {!t.required && (
                      <span style={{ fontSize: 11, color: "#6b7280", background: "#f4f5f7", borderRadius: 5, padding: "1px 6px" }}>
                        可选
                      </span>
                    )}
                    {t.dependsOn.length > 0 && (
                      <span style={{ fontSize: 11, color: "#6b7280" }}>
                        依赖：{t.dependsOn.join(", ")}
                      </span>
                    )}
                    {t.state === "SUCCEEDED" && (
                      t.evidenceValid
                        ? <span style={S.evOkBadge}>证据有效</span>
                        : <span style={S.evFailBadge}>证据已失效</span>
                    )}
                    <span style={{ flex: 1 }} />
                    {t.result?.settledTokens != null && (
                      <span style={{ fontSize: 11, color: "#6b7280" }}>
                        {t.result.settledTokens} tokens · {t.result.durationMs}ms
                      </span>
                    )}
                  </div>
                  <div style={{ fontSize: 12.5, color: "#4b5563", marginTop: 3 }}>{t.goal}</div>
                  {rootRun?.id === plan.rootRunId && <TaskCheckPreviewPanel
                    key={taskCheckPreviewScopeKey({ rootRunId: plan.rootRunId, planId: plan.planId, planRevision: plan.revision, task: t })}
                    scope={{ rootRunId: plan.rootRunId, planId: plan.planId, planRevision: plan.revision, task: t }} />}
                  {reason && (
                    <div style={{ fontSize: 11.5, color: "#b45309", marginTop: 2 }}>原因：{reason}</div>
                  )}
                  {t.result?.errorMessage && (
                    <div style={{ fontSize: 11.5, color: "#b91c1c", marginTop: 2, whiteSpace: "pre-wrap" }}>
                      {t.result.errorMessage}
                    </div>
                  )}
                  {(t.result?.artifactRefs.length || t.result?.verificationRef || t.result?.patchArtifactId) ? (
                    <div style={{ display: "flex", flexWrap: "wrap", gap: 6, marginTop: 5 }}>
                      {t.result?.artifactRefs.map((ref) => (
                        <a key={ref} href={artifactContentUrl(ref)} target="_blank" rel="noreferrer"
                          style={{ fontSize: 11, color: "#2563eb", textDecoration: "underline" }}>
                          产物 {shortId(ref)}
                        </a>
                      ))}
                      {t.result?.verificationRef && (
                        <a href={artifactContentUrl(t.result.verificationRef)} target="_blank" rel="noreferrer"
                          style={{ fontSize: 11, color: "#15803d", textDecoration: "underline" }}>
                          验收记录 {shortId(t.result.verificationRef)}
                        </a>
                      )}
                      {t.result?.patchArtifactId && (
                        <a href={artifactContentUrl(t.result.patchArtifactId)} target="_blank" rel="noreferrer"
                          style={{ fontSize: 11, color: "#1d4ed8", textDecoration: "underline" }}>
                          补丁 {shortId(t.result.patchArtifactId)}
                        </a>
                      )}
                    </div>
                  ) : null}
                </div>
              );
            })}
          </div>
        ))}
        {(rootRun?.budget || childRuns.some((r) => r.budget)) && (
          <div style={{ borderTop: "1px solid #eef0f2", paddingTop: 8, marginTop: 2 }}>
            <div style={{ fontSize: 12, fontWeight: 600, color: "#374151", marginBottom: 4 }}>预算口径（tokens）</div>
            {rootRun?.budget && <BudgetLine label="根 Run" budget={rootRun.budget} />}
            {childRuns.filter((r) => r.budget).map((r) => (
              <BudgetLine key={r.id} label={`子 ${r.role ?? ""} ${shortId(r.id, 8)}`} budget={r.budget!} />
            ))}
          </div>
        )}
      </div>
    </div>
  );
}

/* -------------------------- 运行证据卡（D-17a） -------------------------- */

/**
 * 工作台证据视图：单个 Run 的失败原因（结构化）、Trace 链路（事件自带
 * traceId/spanId/parentRunId）与验证证据（含日志 Artifact 直链）。默认收起。
 */
function EvidenceCard({ runId, events, verifications }: {
  runId: string;
  events: EventEnvelope[];
  verifications: Verification[];
}) {
  const [open, setOpen] = useState(false);
  const [logPreview, setLogPreview] = useState<{ id: string; text: string } | null>(null);

  const runEvents = useMemo(
    () =>
      events
        .filter((e) => e.runId === runId && e.type !== "assistant.text.delta")
        .sort((a, b) => (BigInt(a.sequence) > BigInt(b.sequence) ? 1 : -1)),
    [events, runId],
  );
  const failed = runEvents.find((e) => e.type === "run.failed");
  const cancelled = runEvents.find((e) => e.type === "run.cancelled");
  const completed = runEvents.find((e) => e.type === "run.completed");
  const hasVerification =
    verifications.length > 0 || runEvents.some((e) => e.type === "verification.completed");

  const showLog = async (artifactId: string) => {
    if (logPreview?.id === artifactId) {
      setLogPreview(null);
      return;
    }
    setLogPreview({ id: artifactId, text: "加载中…" });
    try {
      const res = await fetch(artifactContentUrl(artifactId), {
        headers: { "X-DSH-Client": "local-ui" },
      });
      setLogPreview({ id: artifactId, text: res.ok ? await res.text() : `HTTP ${res.status}` });
    } catch (e) {
      setLogPreview({ id: artifactId, text: String(e) });
    }
  };

  return (
    <div style={S.toolCard}>
      <div
        role="button"
        tabIndex={0}
        style={S.toolHead}
        onClick={() => setOpen((v) => !v)}
        onKeyDown={(e) => e.key === "Enter" && setOpen((v) => !v)}
      >
        <span
          style={{
            width: 8, height: 8, borderRadius: 8, flex: "0 0 auto",
            background: failed ? "#dc2626" : cancelled ? "#8a909c" : completed ? "#16a34a" : "#9ca3af",
          }}
        />
        <span style={{ fontSize: 13, fontWeight: 500, color: "#374151" }}>运行证据</span>
        <span style={{ fontSize: 11, color: "#9ca3af" }}>{runId}</span>
        {failed && (
          <span style={S.evFailBadge}>失败 {String(failed.payload.reason ?? "")}</span>
        )}
        {!failed && cancelled && <span style={S.evNeutralBadge}>已取消</span>}
        {!failed && !cancelled && completed && <span style={S.evOkBadge}>已完成</span>}
        <span style={{ flex: 1 }} />
        <span style={{ fontSize: 11, color: "#9ca3af" }}>{runEvents.length} 条链路</span>
        {I.chevron(11, open)}
      </div>
      {open && (
        <div style={S.toolBody}>
          {(failed || cancelled) && (
            <>
              <div style={S.toolLabel}>失败原因</div>
              {failed && (
                <pre style={S.toolPre}>
                  {`reason:  ${String(failed.payload.reason ?? "—")}\nmessage: ${String(failed.payload.message ?? "—")}`}
                </pre>
              )}
              {cancelled && (
                <div style={{ fontSize: 12.5, color: "#6b7280" }}>运行已被用户取消。</div>
              )}
            </>
          )}

          <div style={S.toolLabel}>
            Trace 链路（traceId {String(runEvents[0]?.traceId ?? "—")}）
          </div>
          {runEvents.length === 0 && (
            <div style={{ fontSize: 12.5, color: "#9aa0aa" }}>暂无事件</div>
          )}
          {runEvents.map((e) => (
            <div key={e.eventId} style={S.traceRow}>
              <span style={S.traceTime}>{new Date(e.timestamp).toLocaleTimeString()}</span>
              <span style={S.traceBadge}>{traceTypeLabel(e.type, e.payload)}</span>
              {e.parentRunId && e.parentRunId !== runId && (
                <span style={S.traceBadge}>子 Run …{e.parentRunId.slice(-6)}</span>
              )}
              <span style={{ fontSize: 12.5, color: "#4b5563", wordBreak: "break-all" }}>
                {traceDetail(e)}
              </span>
            </div>
          ))}

          <div style={S.toolLabel}>验证证据</div>
          {!hasVerification && (
            <div style={{ fontSize: 12.5, color: "#9aa0aa" }}>（无验证记录）</div>
          )}
          {verifications.map((v) => (
            <div key={v.id} style={S.traceRow}>
              <span
                style={{
                  ...S.traceBadge,
                  ...(v.status === "PASSED" ? S.evOkBadge : S.evFailBadge),
                }}
              >
                验证 {v.status}
                {v.stale ? " · 已过期" : ""}
              </span>
              <span style={{ fontSize: 12.5, color: "#4b5563" }}>
                exit {v.exitCode} · 测试 {v.testsPassed}/{v.testsDiscovered}
                （跳过 {v.testsSkipped}）· {new Date(v.startedAt).toLocaleTimeString()}
              </span>
              {v.logArtifactIds.map((id) => (
                <button key={id} style={S.traceLink} onClick={() => void showLog(id)}>
                  日志 …{id.slice(-6)}
                </button>
              ))}
            </div>
          ))}
          {logPreview && (
            <>
              <div style={S.toolLabel}>验证日志 {logPreview.id}</div>
              <pre style={S.toolPre}>{logPreview.text}</pre>
            </>
          )}
        </div>
      )}
    </div>
  );
}

function traceTypeLabel(type: string, payload: Record<string, unknown>): string {
  switch (type) {
    case "run.input_applied": return "输入";
    case "run.state_changed": return `→ ${String(payload.to ?? "")}`;
    case "run.completed": return "完成";
    case "run.failed": return "失败";
    case "run.cancelled": return "取消";
    case "model.completed": return "模型";
    case "assistant.message.completed": return "回答";
    case "tool.started": return "工具开始";
    case "tool.completed": return "工具结束";
    case "approval.requested": return "审批请求";
    case "approval.resolved": return "审批结论";
    case "checkpoint.created": return "检查点";
    case "verification.completed": return "验证";
    default: return type;
  }
}

function traceDetail(e: EventEnvelope): string {
  const p = e.payload as Record<string, unknown>;
  switch (e.type) {
    case "tool.started":
    case "tool.completed":
      return String(p.name ?? "");
    case "approval.requested":
      return String(p.toolName ?? "");
    case "approval.resolved":
      return String(p.status ?? "");
    case "assistant.message.completed":
      return String(p.content ?? "").slice(0, 80);
    case "run.failed":
      return String(p.reason ?? "");
    case "verification.completed":
      return `${String(p.status ?? "")}${p.stale ? "（已过期）" : ""}`;
    default:
      return "";
  }
}

/* ------------------------------- Composer ------------------------------- */

function Composer(props: {
  project: Project | null;
  projects: Project[];
  profiles: ModelProfile[];
  profileId: string;
  running: boolean;
  statusText: string;
  lockProject?: boolean;
  value?: string;
  onValue?: (v: string) => void;
  onProfileChange: (id: string) => void;
  onProjectChange: (id: string) => void;
  onOpenSettings: (tab: "models" | "project") => void;
  onSend: (text: string, options: TurnSubmissionOptions) => Promise<boolean>;
  onAppend?: (text: string) => Promise<boolean>;
  initialRequirement?: TurnSubmissionOptions["verification"];
  onCancel: () => void;
}) {
  const [internal, setInternal] = useState("");
  const value = props.value !== undefined ? props.value : internal;
  const setValue = (v: string) => {
    if (props.onValue) props.onValue(v);
    else setInternal(v);
  };
  const [prepared, setPrepared] = useState<{ projectId: string; options: TurnSubmissionOptions | null } | null>(null);
  const [editorRevision, setEditorRevision] = useState(0);
  const [sending, setSending] = useState(false);
  const onPrepared = useCallback((projectId: string, options: TurnSubmissionOptions | null) => setPrepared({ projectId, options }), []);
  const options = prepared && prepared.projectId === props.project?.id ? prepared.options : null;
  const [menu, setMenu] = useState<"" | "plus" | "model" | "project">("");
  const taRef = useRef<HTMLTextAreaElement>(null);

  useEffect(() => {
    const ta = taRef.current;
    if (!ta) return;
    ta.style.height = "auto";
    ta.style.height = Math.min(ta.scrollHeight, 160) + "px";
  }, [value]);

  const submit = async () => {
    const text = value.trim();
    const append = props.running && !!props.onAppend;
    if (!text || sending || (props.running && !append) || (!append && !options)) return;
    setSending(true);
    try {
      if (append ? await props.onAppend!(text) : options && await props.onSend(text, options)) {
        setValue(""); setMenu("");
        if (!append) { setPrepared(null); setEditorRevision(n => n + 1); }
      }
    } finally { setSending(false); }
  };

  const projectName = props.project?.name ?? "未选择项目";

  return (
    <div style={S.composerCard} onMouseDown={(e) => e.stopPropagation()}>
      {props.project && <VerificationRequirementEditor key={props.project.id + ":" + editorRevision} projectId={props.project.id} disabled={props.running || sending} onPrepared={onPrepared} initialRequirement={editorRevision === 0 ? props.initialRequirement : undefined} />}
      {props.running && props.onAppend && <div style={{ fontSize: 12 }}>可补充需求或追加约束；本次验收登记保持不变。</div>}
      <div style={S.chipRow}>
        <div style={{ position: "relative" }}>
          <button
            style={S.chipBtn}
            disabled={props.lockProject}
            onClick={() => setMenu(menu === "project" ? "" : "project")}
            title={props.lockProject ? "当前会话所属项目" : "切换项目"}
          >
            {I.folder(13)}
            <span>{projectName}</span>
            {!props.lockProject && I.chevron(10)}
          </button>
          {menu === "project" && (
            <Popover onClose={() => setMenu("")} align="left">
              {props.projects.map((p) => (
                <button
                  key={p.id}
                  style={S.menuItem}
                  onClick={() => {
                    props.onProjectChange(p.id);
                    setMenu("");
                  }}
                >
                  {I.folder(13)} {p.name}
                </button>
              ))}
            </Popover>
          )}
        </div>
      </div>

      <textarea
        ref={taRef}
        style={S.textarea}
        placeholder="随心输入…"
        rows={1}
        value={value}
        disabled={!props.project}
        onChange={(e) => setValue(e.target.value)}
        onKeyDown={(e) => {
          if (e.key === "Enter" && !e.shiftKey && !e.nativeEvent.isComposing) {
            e.preventDefault();
            void submit();
          }
        }}
      />

      <div style={S.composerBar}>
        <div style={{ position: "relative" }}>
          <button style={S.roundBtn} title="更多" onClick={() => setMenu(menu === "plus" ? "" : "plus")}>
            {I.plus(15)}
          </button>
          {menu === "plus" && (
            <Popover onClose={() => setMenu("")} align="left">
              <button style={S.menuItem} onClick={() => { setMenu(""); props.onOpenSettings("project"); }}>
                {I.folder(13)} 新建项目…
              </button>
              <button style={S.menuItem} onClick={() => { setMenu(""); props.onOpenSettings("models"); }}>
                {I.gear(14)} 模型设置…
              </button>
            </Popover>
          )}
        </div>

        <span style={{ flex: 1 }} />

        <div style={{ position: "relative" }}>
          <button style={S.modelBtn} onClick={() => setMenu(menu === "model" ? "" : "model")}>
            <span>{props.profiles.find((p) => p.id === props.profileId)?.name ?? "选择模型"}</span>
            {I.chevron(10)}
          </button>
          {menu === "model" && (
            <Popover onClose={() => setMenu("")} align="right">
              {props.profiles.length === 0 && <div style={S.menuHint}>暂无模型配置</div>}
              {props.profiles.map((p) => (
                <button
                  key={p.id}
                  style={S.menuItem}
                  onClick={() => {
                    props.onProfileChange(p.id);
                    setMenu("");
                  }}
                >
                  <span style={{ minWidth: 12 }}>{p.id === props.profileId ? "✓" : ""}</span>
                  {p.name} <span style={S.menuHint}>（{p.model}）</span>
                </button>
              ))}
              <div style={S.menuDivider} />
              <button style={S.menuItem} onClick={() => { setMenu(""); props.onOpenSettings("models"); }}>
                {I.plus(13)} 添加模型配置…
              </button>
            </Popover>
          )}
        </div>

        {props.running ? (
          <>
          {props.onAppend && <button style={S.sendBtn} title="追加约束" disabled={!value.trim() || sending} onClick={() => void submit()}>{I.send(15)}</button>}
          <button style={S.stopBtn} title="取消运行" onClick={props.onCancel}>
            {I.stop(12)}
          </button>
          </>
        ) : (
          <button
            style={{ ...S.sendBtn, ...(!value.trim() || !props.project || !options || sending ? S.sendBtnDisabled : {}) }}
            disabled={!value.trim() || !props.project || !options || sending}
            title="发送"
            onClick={() => void submit()}
          >
            {I.send(15)}
          </button>
        )}
      </div>
    </div>
  );
}

function Popover({ children, onClose, align }: { children: ReactNode; onClose: () => void; align: "left" | "right" }) {
  return (
    <>
      <div style={{ position: "fixed", inset: 0, zIndex: 40 }} onClick={onClose} />
      <div style={{ ...S.popover, ...(align === "right" ? { right: 0 } : { left: 0 }) }}>{children}</div>
    </>
  );
}

/* ------------------------------ Settings modal ------------------------------ */

function SettingsModal(props: {
  initialTab: "models" | "project";
  profiles: ModelProfile[];
  onClose: () => void;
  onProfilesChanged: (profiles: ModelProfile[], selectedId?: string) => void;
  onProjectCreated: (p: Project) => void;
}) {
  const [tab, setTab] = useState(props.initialTab);
  return (
    <div style={S.overlay} onClick={props.onClose}>
      <div style={S.modal} onClick={(e) => e.stopPropagation()}>
        <div style={S.modalHeader}>
          <div style={S.modalTabs}>
            <button style={tab === "models" ? S.modalTabActive : S.modalTab} onClick={() => setTab("models")}>
              模型配置
            </button>
            <button style={tab === "project" ? S.modalTabActive : S.modalTab} onClick={() => setTab("project")}>
              项目
            </button>
          </div>
          <button style={S.iconBtn} onClick={props.onClose}>{I.close(15)}</button>
        </div>
        {tab === "models" ? <ModelsPanel profiles={props.profiles} onChanged={props.onProfilesChanged} /> : <ProjectPanel onCreated={props.onProjectCreated} />}
      </div>
    </div>
  );
}

function ModelsPanel({ profiles, onChanged }: {
  profiles: ModelProfile[];
  onChanged: (profiles: ModelProfile[], selectedId?: string) => void;
}) {
  const [selected, setSelected] = useState(profiles[0]?.id ?? "");
  const [name, setName] = useState("DeepSeek 主力");
  const [provider, setProvider] = useState<"deepseek" | "openai-compat">("deepseek");
  const [model, setModel] = useState("deepseek-chat");
  const [baseUrl, setBaseUrl] = useState("https://api.deepseek.com/v1");
  const [thinking, setThinking] = useState<"disabled" | "enabled">("disabled");
  const [apiKey, setApiKey] = useState("");
  const [busy, setBusy] = useState(false);
  const [msg, setMsg] = useState("");
  const [error, setError] = useState("");

  const validate = async (level: "CONNECTIVITY" | "TOOL_ROUNDTRIP") => {
    if (!selected) return;
    setBusy(true);
    setMsg("");
    setError("");
    try {
      const v = await validateModelProfile(selected, level);
      setMsg(v.passed
        ? level === "CONNECTIVITY" ? "连通性验证通过" : "完整往返验证通过：可正常对话并调用工具"
        : "验证未通过：请检查 API Key / Base URL / 网络");
    } catch (e) {
      setError((e as ApiError).message);
    } finally {
      setBusy(false);
    }
  };

  const save = async () => {
    setError("");
    if (!name.trim() || !model.trim()) return setError("名称与模型 ID 必填");
    if (!apiKey.trim()) return setError("请先粘贴 API Key");
    if (provider === "openai-compat" && !baseUrl.trim()) return setError("openai-compat 需要填写 Base URL");
    setBusy(true);
    try {
      const ref = name.toLowerCase().replace(/[^a-z0-9_-]+/g, "-").replace(/^-+|-+$/g, "").slice(0, 64) || "local-key";
      await saveCredential(ref, apiKey.trim());
      const created = await createModelProfile({
        name: name.trim(),
        provider,
        model: model.trim(),
        baseUrl: baseUrl.trim() || "https://api.deepseek.com/v1",
        credentialRef: ref,
        options: { thinking, maxOutputTokens: 8192, streaming: false },
      });
      const next = [...profiles, created];
      onChanged(next, created.id);
      setSelected(created.id);
      setApiKey("");
      setMsg(`配置已保存（凭据 ${ref}），正在做连通性验证…`);
      const v = await validateModelProfile(created.id, "CONNECTIVITY");
      setMsg(v.passed ? `配置已保存，连通性验证通过（凭据 ${ref}）` : "配置已保存，但连通性验证未通过");
    } catch (e) {
      setError((e as ApiError).message);
    } finally {
      setBusy(false);
    }
  };

  return (
    <div style={S.modalBody}>
      <label style={S.field}>
        <span style={S.fieldLabel}>已有配置</span>
        <select style={S.sel} value={selected} onChange={(e) => setSelected(e.target.value)}>
          {profiles.length === 0 && <option value="">（暂无）</option>}
          {profiles.map((p) => (
            <option key={p.id} value={p.id}>{p.name}（{p.provider}/{p.model}）</option>
          ))}
        </select>
      </label>
      <div style={{ display: "flex", gap: 8 }}>
        <button style={S.secBtn} disabled={!selected || busy} onClick={() => void validate("TOOL_ROUNDTRIP")}>
          完整往返验证所选模型
        </button>
      </div>

      <div style={S.formDivider}>添加模型配置</div>
      <div style={S.formGrid}>
        <label style={S.field}>
          <span style={S.fieldLabel}>供应商</span>
          <select
            style={S.sel}
            value={provider}
            onChange={(e) => {
              const p = e.target.value as "deepseek" | "openai-compat";
              setProvider(p);
              if (p === "deepseek") {
                setModel("deepseek-chat");
                setBaseUrl("https://api.deepseek.com/v1");
              } else {
                setModel("");
                setBaseUrl("");
              }
            }}
          >
            <option value="deepseek">deepseek（官方 API）</option>
            <option value="openai-compat">openai-compat（OpenAI 兼容端点）</option>
          </select>
        </label>
        <label style={S.field}>
          <span style={S.fieldLabel}>名称</span>
          <input style={S.inp} value={name} onChange={(e) => setName(e.target.value)} />
        </label>
        <label style={S.field}>
          <span style={S.fieldLabel}>模型 ID</span>
          <input
            style={S.inp}
            value={model}
            placeholder={provider === "deepseek" ? "deepseek-chat" : "gpt-4o-mini 等"}
            onChange={(e) => setModel(e.target.value)}
          />
        </label>
        <label style={S.field}>
          <span style={S.fieldLabel}>Base URL</span>
          <input
            style={S.inp}
            value={baseUrl}
            placeholder={provider === "deepseek" ? "https://api.deepseek.com/v1" : "http://host:port/v1"}
            onChange={(e) => setBaseUrl(e.target.value)}
          />
        </label>
        <label style={S.field}>
          <span style={S.fieldLabel}>思考模式{provider === "openai-compat" ? "（对 openai-compat 忽略）" : ""}</span>
          <select
            style={S.sel}
            value={thinking}
            disabled={provider === "openai-compat"}
            onChange={(e) => setThinking(e.target.value as "disabled" | "enabled")}
          >
            <option value="disabled">disabled（deepseek-chat）</option>
            <option value="enabled">enabled（deepseek-reasoner）</option>
          </select>
        </label>
        <label style={S.field}>
          <span style={S.fieldLabel}>API Key（仅保存在本机 data/credentials.properties）</span>
          <input style={S.inp} type="password" placeholder="sk-..." value={apiKey} onChange={(e) => setApiKey(e.target.value)} />
        </label>
      </div>
      <div style={{ display: "flex", gap: 8, marginTop: 10 }}>
        <button style={S.primaryBtn} disabled={busy} onClick={() => void save()}>
          {busy ? "处理中…" : "保存 Key 并创建配置"}
        </button>
      </div>
      {msg && <div style={S.okText}>{msg}</div>}
      {error && <div style={S.errText}>{error}</div>}
    </div>
  );
}

function ProjectPanel({ onCreated }: { onCreated: (p: Project) => void }) {
  const [name, setName] = useState("");
  const [rootPath, setRootPath] = useState("D:/code/MyAgent/DSH/backend/target/test-run");
  const [picker, setPicker] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [msg, setMsg] = useState("");

  const create = async () => {
    if (!name.trim() || !rootPath.trim()) {
      setError("项目名与根目录必填");
      return;
    }
    setBusy(true);
    setError("");
    try {
      const p = await createProject(name.trim(), rootPath.trim());
      await trustProject(p.id, true, p.version);
      onCreated(p);
      setMsg("项目已创建并信任");
      setName("");
    } catch (e) {
      setError((e as ApiError).message);
    } finally {
      setBusy(false);
    }
  };

  return (
    <div style={S.modalBody}>
      <label style={S.field}>
        <span style={S.fieldLabel}>项目名</span>
        <input style={S.inp} value={name} placeholder="例如 my-app" onChange={(e) => setName(e.target.value)} />
      </label>
      <label style={S.field}>
        <span style={S.fieldLabel}>根目录绝对路径</span>
        <div style={{ display: "flex", gap: 8 }}>
          <input style={{ ...S.inp, flex: 1 }} value={rootPath} onChange={(e) => setRootPath(e.target.value)} />
          <button style={S.secBtn} onClick={() => setPicker(true)}>浏览…</button>
        </div>
      </label>
      <div style={{ display: "flex", gap: 8, marginTop: 10 }}>
        <button style={S.primaryBtn} disabled={busy} onClick={() => void create()}>
          {busy ? "处理中…" : "创建并信任"}
        </button>
      </div>
      {msg && <div style={S.okText}>{msg}</div>}
      {error && <div style={S.errText}>{error}</div>}
      {picker && (
        <FolderPickerDialog
          initial={rootPath}
          onPick={(p) => {
            setRootPath(p);
            setPicker(false);
          }}
          onClose={() => setPicker(false)}
        />
      )}
    </div>
  );
}

/* --------------------------- 服务端文件夹浏览器 --------------------------- */

function FolderPickerDialog({ initial, onPick, onClose }: {
  initial?: string;
  onPick: (path: string) => void;
  onClose: () => void;
}) {
  const [listing, setListing] = useState<FsListing | null>(null);
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(false);

  const load = useCallback(async (p?: string) => {
    setLoading(true);
    setError("");
    try {
      setListing(await listFsEntries(p));
    } catch (e) {
      setError((e as ApiError).message);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void load(initial);
  }, [load, initial]);

  const crumbs = useMemo(() => {
    if (!listing?.path) return [];
    const parts = listing.path.split(/[\\/]+/).filter(Boolean);
    return parts.map((seg, i) => {
      const isDrive = /^[A-Za-z]:$/.test(seg);
      return {
        label: seg,
        path: isDrive ? seg + "\\" : parts.slice(0, i + 1).join("\\"),
      };
    });
  }, [listing]);

  return (
    <div style={S.overlay} onClick={onClose}>
      <div style={{ ...S.modal, width: 560 }} onClick={(e) => e.stopPropagation()}>
        <div style={S.modalHeader}>
          <strong>选择工作区文件夹</strong>
          <button style={S.iconBtn} onClick={onClose}>{I.close(15)}</button>
        </div>
        <div style={S.crumbRow}>
          {crumbs.length === 0 && <span style={S.hint}>我的电脑（盘符 / 授权根）</span>}
          {crumbs.map((c, i) => (
            <span key={c.path}>
              {i > 0 && <span style={{ color: "#9ca3af" }}> / </span>}
              <button style={S.crumbBtn} onClick={() => void load(c.path)}>{c.label}</button>
            </span>
          ))}
        </div>
        <div style={S.dirList}>
          {listing?.parent && (
            <button style={S.dirRow} onClick={() => void load(listing.parent ?? undefined)}>⬆ 上级目录</button>
          )}
          {loading && <div style={S.hint}>加载中…</div>}
          {!loading && (listing?.directories.length ?? 0) === 0 && <div style={S.hint}>（没有子目录）</div>}
          {listing?.directories.map((d) => (
            <button key={d.path} style={S.dirRow} onClick={() => void load(d.path)}>📁 {d.name}</button>
          ))}
        </div>
        {error && <div style={S.errText}>{error}</div>}
        <div style={{ display: "flex", justifyContent: "flex-end", gap: 8, marginTop: 12 }}>
          <button style={S.secBtn} onClick={onClose}>取消</button>
          <button style={S.primaryBtn} disabled={!listing?.path}
            onClick={() => listing?.path && onPick(listing.path)}>
            选择当前目录
          </button>
        </div>
      </div>
    </div>
  );
}

/* -------------------------------- Styles -------------------------------- */

const S: Record<string, CSSProperties> = {
  shell: {
    display: "flex",
    height: "100vh",
    width: "100vw",
    overflow: "hidden",
    background: "#fff",
  },

  /* sidebar */
  sidebar: {
    width: 268,
    flex: "0 0 268px",
    background: "#f7f7f8",
    borderRight: "1px solid #e9e9ec",
    display: "flex",
    flexDirection: "column",
    padding: "12px 10px 10px",
  },
  sideHeader: { display: "flex", alignItems: "center", gap: 5, padding: "4px 6px 12px" },
  brand: { fontSize: 19, fontWeight: 700, letterSpacing: 0.2, marginRight: 2 },
  iconBtn: {
    background: "none", border: "none", cursor: "pointer", padding: 5, borderRadius: 8,
    color: "#5b6270", display: "inline-flex", alignItems: "center", justifyContent: "center",
  },
  newChatBtn: {
    display: "flex", alignItems: "center", justifyContent: "space-between",
    width: "100%", padding: "9px 10px", border: "none", cursor: "pointer",
    background: "transparent", borderRadius: 10, fontSize: 14, color: "#1f2328",
    marginBottom: 10,
  },
  sectionRow: {
    display: "flex", alignItems: "center", justifyContent: "space-between",
    padding: "6px 8px 4px",
  },
  sectionLabel: { fontSize: 11.5, color: "#9aa0aa", fontWeight: 600, letterSpacing: 0.5 },
  sideList: { flex: 1, overflowY: "auto", paddingRight: 2 },
  sideHint: { fontSize: 12.5, color: "#a8adb7", padding: "6px 10px" },
  sideFooter: {
    display: "flex", alignItems: "center", gap: 8,
    borderTop: "1px solid #e9e9ec", padding: "10px 10px 2px",
  },
  projectRow: {
    display: "flex", alignItems: "center", gap: 7,
    padding: "7px 9px", borderRadius: 9, cursor: "pointer",
    fontSize: 14, color: "#1f2328", userSelect: "none",
  },
  rowActive: { background: "#e9eef7", fontWeight: 600 },
  threadGroup: { paddingLeft: 22 },
  threadRow: {
    display: "flex", alignItems: "center", gap: 8,
    padding: "6px 9px", borderRadius: 9, cursor: "pointer",
    fontSize: 13.5, color: "#4b5563", userSelect: "none",
  },
  threadRowActive: { background: "#e9eef7", color: "#1f2328", fontWeight: 600 },
  rowText: {
    flex: 1, overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap",
  },
  rowDel: {
    color: "#9aa0aa", padding: 2, borderRadius: 6,
    display: "inline-flex", alignItems: "center", flex: "0 0 auto",
    cursor: "pointer",
  },

  /* main pane */
  main: { flex: 1, display: "flex", flexDirection: "column", minWidth: 0, background: "#fff" },
  pane: { flex: 1, display: "flex", flexDirection: "column", minHeight: 0 },
  welcomeWrap: {
    flex: 1, display: "flex", flexDirection: "column",
    alignItems: "center", justifyContent: "center", padding: 20,
  },
  welcomeTitle: { fontSize: 30, fontWeight: 600, margin: 0, color: "#1f2328", textAlign: "center", lineHeight: 1.4 },
  welcomeProject: { textDecoration: "underline", textDecorationColor: "#9aa0aa", textUnderlineOffset: 5, margin: "0 4px" },

  /* timeline */
  timeline: { flex: 1, overflowY: "auto", minHeight: 0 },
  timelineInner: { maxWidth: 820, margin: "0 auto", padding: "28px 24px 12px", display: "flex", flexDirection: "column", gap: 12 },
  chatEmpty: { flex: 1, display: "flex", flexDirection: "column", alignItems: "center", justifyContent: "center", padding: "60px 0" },
  bubbleRow: { display: "flex", justifyContent: "flex-start" },
  userBubble: {
    background: "#f3f4f6", borderRadius: "6px 18px 18px 18px",
    padding: "10px 15px", maxWidth: "88%",
  },
  assistantBlock: { display: "flex", flexDirection: "column", gap: 4, padding: "2px 2px" },
  bubbleText: { fontSize: 14.5, lineHeight: 1.7, whiteSpace: "pre-wrap", wordBreak: "break-word" },
  itemTime: { fontSize: 11, color: "#b6bbc4" },
  inlineRow: { display: "flex", alignItems: "center", gap: 8, padding: "2px 4px" },
  toolCard: {
    border: "1px solid #eceef1", borderRadius: 12, background: "#fbfbfc",
    overflow: "hidden",
  },
  toolHead: {
    display: "flex", alignItems: "center", gap: 9,
    padding: "8px 12px", cursor: "pointer", userSelect: "none",
  },
  toolBody: { padding: "2px 12px 10px", borderTop: "1px solid #f0f1f3" },
  toolLabel: { fontSize: 11.5, color: "#9aa0aa", margin: "8px 0 4px" },
  toolPre: {
    margin: 0, fontSize: 12.5, lineHeight: 1.6, whiteSpace: "pre-wrap",
    wordBreak: "break-word", background: "#f4f5f7", borderRadius: 8, padding: "8px 10px",
    fontFamily: "ui-monospace, SFMono-Regular, Menlo, Consolas, monospace",
    maxHeight: 280, overflowY: "auto",
  },
  thinkingRow: { display: "flex", alignItems: "center", gap: 10, padding: "6px 4px" },
  dots: { display: "inline-flex", gap: 4 },
  diffStatAdded: { fontSize: 11, fontWeight: 700, color: "#15803d", background: "#dcfce7", borderRadius: 5, padding: "1px 6px" },
  diffStatModified: { fontSize: 11, fontWeight: 700, color: "#1d4ed8", background: "#dbeafe", borderRadius: 5, padding: "1px 6px" },
  diffStatDeleted: { fontSize: 11, fontWeight: 700, color: "#b91c1c", background: "#fee2e2", borderRadius: 5, padding: "1px 6px" },
  diffFileRow: { display: "flex", alignItems: "center", gap: 8, padding: "3px 0" },

  /* 运行证据卡（D-17a） */
  traceRow: { display: "flex", alignItems: "baseline", gap: 8, padding: "3px 0" },
  traceTime: {
    fontSize: 11, color: "#b6bbc4", flex: "0 0 auto",
    fontFamily: "ui-monospace, SFMono-Regular, Menlo, Consolas, monospace",
  },
  traceBadge: {
    fontSize: 11, fontWeight: 600, color: "#4b5563", background: "#eef0f2",
    borderRadius: 5, padding: "1px 6px", flex: "0 0 auto",
  },
  traceLink: {
    background: "none", border: "none", color: "#2563eb", cursor: "pointer",
    fontSize: 12, padding: 0, textDecoration: "underline", flex: "0 0 auto",
  },
  evOkBadge: {
    fontSize: 11, fontWeight: 700, color: "#15803d", background: "#dcfce7",
    borderRadius: 5, padding: "1px 6px", flex: "0 0 auto",
  },
  evFailBadge: {
    fontSize: 11, fontWeight: 700, color: "#b91c1c", background: "#fee2e2",
    borderRadius: 5, padding: "1px 6px", flex: "0 0 auto",
  },
  evNeutralBadge: {
    fontSize: 11, fontWeight: 700, color: "#6b7280", background: "#eef0f2",
    borderRadius: 5, padding: "1px 6px", flex: "0 0 auto",
  },

  approvalStack: {
    display: "flex", flexDirection: "column", gap: 10,
    maxWidth: 860, width: "100%", margin: "0 auto 10px", padding: "0 24px",
  },
  approvalCard: {
    border: "1px solid #f0d488", background: "#fffdf4",
    borderRadius: 16, padding: "12px 16px",
    boxShadow: "0 2px 10px rgba(180,120,20,0.08)",
  },
  approvalHead: { display: "flex", alignItems: "center", gap: 9 },
  approvalRisk: {
    marginLeft: "auto", fontSize: 11, fontWeight: 700, color: "#92400e",
    background: "#fef3c7", borderRadius: 6, padding: "2px 8px",
  },
  approvalArgs: {
    margin: "10px 0 0", fontSize: 12.5, lineHeight: 1.6, whiteSpace: "pre-wrap",
    wordBreak: "break-word", background: "#fff7de", borderRadius: 8, padding: "8px 10px",
    fontFamily: "ui-monospace, SFMono-Regular, Menlo, Consolas, monospace",
    maxHeight: 160, overflowY: "auto",
  },
  approvalReason: { fontSize: 12.5, color: "#92400e", marginTop: 8 },
  approvalActions: { display: "flex", gap: 10, marginTop: 12 },
  approveBtn: {
    background: "#2563eb", color: "#fff", border: "none", borderRadius: 10,
    padding: "8px 20px", fontSize: 13.5, fontWeight: 600, cursor: "pointer",
  },
  denyBtn: {
    background: "#fff", color: "#b45309", border: "1px solid #f0d488", borderRadius: 10,
    padding: "8px 20px", fontSize: 13.5, cursor: "pointer",
  },

  /* composer dock */
  composerDock: { padding: "8px 24px 22px" },
  composerError: { maxWidth: 860, margin: "8px auto 0", fontSize: 12.5, color: "#dc2626" },
  composerCard: {
    maxWidth: 860, margin: "0 auto",
    border: "1px solid #e2e4e8", borderRadius: 24, background: "#fff",
    boxShadow: "0 4px 18px rgba(15,23,42,0.07)",
    padding: "11px 14px 9px",
  },
  chipRow: { display: "flex", marginBottom: 6 },
  chipBtn: {
    display: "inline-flex", alignItems: "center", gap: 6,
    background: "#f3f4f6", border: "none", borderRadius: 9,
    padding: "5px 10px", fontSize: 13, color: "#374151", cursor: "pointer",
  },
  textarea: {
    width: "100%", border: "none", outline: "none", resize: "none",
    fontSize: 15, lineHeight: 1.6, padding: "6px 4px", fontFamily: "inherit",
    background: "transparent", color: "#1f2328",
  },
  composerBar: { display: "flex", alignItems: "center", gap: 8, marginTop: 4 },
  roundBtn: {
    width: 34, height: 34, borderRadius: 17, border: "1px solid #e2e4e8",
    background: "#fff", cursor: "pointer", color: "#4b5563",
    display: "inline-flex", alignItems: "center", justifyContent: "center",
  },
  modelBtn: {
    display: "inline-flex", alignItems: "center", gap: 6,
    border: "none", background: "transparent", cursor: "pointer",
    fontSize: 13.5, color: "#4b5563", padding: "6px 8px", borderRadius: 8,
  },
  sendBtn: {
    width: 34, height: 34, borderRadius: 17, border: "none",
    background: "#2563eb", color: "#fff", cursor: "pointer",
    display: "inline-flex", alignItems: "center", justifyContent: "center",
  },
  sendBtnDisabled: { background: "#c7d2fe", cursor: "default" },
  stopBtn: {
    width: 34, height: 34, borderRadius: 17,
    border: "1px solid #fecaca", background: "#fff", color: "#dc2626",
    cursor: "pointer", display: "inline-flex", alignItems: "center", justifyContent: "center",
  },
  popover: {
    position: "absolute", bottom: 40, zIndex: 41, minWidth: 200,
    background: "#fff", border: "1px solid #e5e7eb", borderRadius: 12,
    boxShadow: "0 8px 28px rgba(15,23,42,0.14)", padding: 6,
    display: "flex", flexDirection: "column", gap: 1,
  },
  menuItem: {
    display: "flex", alignItems: "center", gap: 8,
    border: "none", background: "none", cursor: "pointer",
    fontSize: 13.5, color: "#374151", padding: "8px 10px",
    borderRadius: 8, textAlign: "left", whiteSpace: "nowrap",
  },
  menuHint: { fontSize: 12, color: "#9aa0aa", padding: "8px 10px" },
  menuDivider: { height: 1, background: "#eef0f2", margin: "4px 2px" },

  /* modal */
  overlay: {
    position: "fixed", inset: 0, zIndex: 60,
    background: "rgba(15,23,42,0.4)",
    display: "flex", alignItems: "center", justifyContent: "center",
  },
  modal: {
    background: "#fff", borderRadius: 14, padding: 18, width: 540,
    maxHeight: "82vh", overflowY: "auto",
    boxShadow: "0 24px 60px rgba(0,0,0,0.25)",
  },
  modalHeader: { display: "flex", alignItems: "center", justifyContent: "space-between", marginBottom: 12 },
  modalTabs: { display: "flex", gap: 4, background: "#f3f4f6", borderRadius: 10, padding: 3 },
  modalTab: { border: "none", background: "transparent", borderRadius: 8, padding: "6px 16px", fontSize: 13.5, color: "#6b7280", cursor: "pointer" },
  modalTabActive: { border: "none", background: "#fff", borderRadius: 8, padding: "6px 16px", fontSize: 13.5, fontWeight: 600, color: "#1f2328", cursor: "pointer", boxShadow: "0 1px 3px rgba(0,0,0,0.1)" },
  modalBody: { display: "flex", flexDirection: "column" },
  field: { display: "flex", flexDirection: "column", gap: 5, marginBottom: 10, fontSize: 12.5, color: "#4b5563" },
  fieldLabel: { color: "#6b7280", fontSize: 12.5 },
  inp: { width: "100%", padding: "8px 10px", fontSize: 13.5, border: "1px solid #d8dbe0", borderRadius: 9, outline: "none", fontFamily: "inherit" },
  sel: { width: "100%", padding: "8px 10px", fontSize: 13.5, border: "1px solid #d8dbe0", borderRadius: 9, background: "#fff", fontFamily: "inherit" },
  secBtn: { padding: "8px 14px", border: "1px solid #d8dbe0", background: "#fff", borderRadius: 9, fontSize: 13, cursor: "pointer" },
  primaryBtn: { padding: "8px 18px", border: "none", background: "#2563eb", color: "#fff", borderRadius: 9, fontSize: 13.5, fontWeight: 600, cursor: "pointer" },
  formDivider: { margin: "14px 0 10px", paddingTop: 12, borderTop: "1px solid #eef0f2", fontSize: 13, fontWeight: 600, color: "#374151" },
  formGrid: { display: "flex", flexDirection: "column", gap: 2 },
  okText: { marginTop: 10, fontSize: 12.5, color: "#15803d" },
  errText: { marginTop: 10, fontSize: 12.5, color: "#dc2626" },
  hint: { fontSize: 12.5, color: "#9aa0aa", padding: 6 },
  crumbRow: { marginBottom: 8, fontSize: 13, color: "#374151", wordBreak: "break-all" },
  crumbBtn: { background: "none", border: "none", color: "#2563eb", cursor: "pointer", padding: 0, fontSize: 13 },
  dirList: { overflowY: "auto", minHeight: 200, maxHeight: 340, border: "1px solid #e5e7eb", borderRadius: 9 },
  dirRow: {
    display: "block", width: "100%", textAlign: "left", padding: "8px 12px",
    background: "none", border: "none", borderBottom: "1px solid #f3f4f6",
    cursor: "pointer", fontSize: 13.5,
  },
};
