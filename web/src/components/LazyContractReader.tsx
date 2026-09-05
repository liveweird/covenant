import { lazy, Suspense, type ComponentProps } from "react";
import LoadingBlock from "./LoadingBlock";

const ContractReader = lazy(() => import("./ContractReader"));

/** The reader rides its own chunk — the version page loads it only when the Reader view is on. */
export default function LazyContractReader(props: ComponentProps<typeof ContractReader>) {
  return (
    <Suspense fallback={<LoadingBlock />}>
      <ContractReader {...props} />
    </Suspense>
  );
}
