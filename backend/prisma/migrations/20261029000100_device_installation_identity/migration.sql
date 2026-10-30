-- Stable installation identity allows re-pairing one app installation
-- without creating duplicate Device rows. Existing devices remain valid.
ALTER TABLE "Device" ADD COLUMN "installationId" TEXT;

CREATE UNIQUE INDEX "Device_familyId_installationId_key"
ON "Device"("familyId", "installationId");
