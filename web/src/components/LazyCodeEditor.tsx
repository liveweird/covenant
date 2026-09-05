import { lazy, Suspense } from "react";
import LoadingBlock from "./LoadingBlock";
import type { CodeEditorProps } from "./CodeEditor";

export type { CodeEditorProps, JumpRequest } from "./CodeEditor";

const CodeEditor = lazy(() => import("./CodeEditor"));

/** The editor behind a lazy boundary — the CodeMirror chunk loads only on a document screen. */
export default function LazyCodeEditor(props: CodeEditorProps) {
  return (
    <Suspense fallback={<LoadingBlock mih={props.minHeight ?? 320} />}>
      <CodeEditor {...props} />
    </Suspense>
  );
}
