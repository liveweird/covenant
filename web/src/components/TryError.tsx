import { Alert } from "@mantine/core";
import { useTranslation } from "react-i18next";
import { tryErrorMessage } from "../utils/tryIt";

/** The try panels' one failure surface: the server's 400/502 detail verbatim, everything else mapped (utils/tryIt.ts). */
export default function TryError({ error }: { error: unknown }) {
  const { t } = useTranslation();
  if (!error) return null;
  return (
    <Alert color="red" variant="light" role="alert">
      {tryErrorMessage(error, t)}
    </Alert>
  );
}
