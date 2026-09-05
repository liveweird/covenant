import { Stack, Text } from "@mantine/core";
import { useTranslation } from "react-i18next";
import PageHeader from "../components/PageHeader";

/**
 * The landing page of the scaffold. The contracts feature replaces it with the Domain →
 * System → Contract hierarchy; until then it only names the app and what is coming, so the
 * shell, the nav model and the e2e journeys have a stable authenticated home.
 */
export default function Home() {
  const { t } = useTranslation();
  return (
    <Stack gap="md">
      <PageHeader title={t("home.title")} description={t("home.description")} />
      <Text size="sm" c="dimmed">
        {t("home.comingSoon")}
      </Text>
    </Stack>
  );
}
