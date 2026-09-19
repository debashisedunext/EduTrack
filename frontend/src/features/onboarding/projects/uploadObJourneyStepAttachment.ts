import { useMutation } from '@tanstack/react-query'

import http from '@/api/http'
import type { ObAttachmentResponse } from '@/api/generated/model/obAttachmentResponse'

export interface UploadObJourneyStepAttachmentBody {
  file: Blob
}

export function uploadObJourneyStepAttachment(
  stepId: number,
  body: UploadObJourneyStepAttachmentBody,
  signal?: AbortSignal,
): Promise<ObAttachmentResponse> {
  const form = new FormData()
  form.append('file', body.file)
  return http<ObAttachmentResponse>({
    url: `/onboarding/journey-steps/${stepId}/attachments`,
    method: 'POST',
    data: form,
    signal,
  })
}

export function useUploadObJourneyStepAttachment() {
  return useMutation({
    mutationFn: ({ stepId, data }: { stepId: number; data: UploadObJourneyStepAttachmentBody }) =>
      uploadObJourneyStepAttachment(stepId, data),
  })
}
