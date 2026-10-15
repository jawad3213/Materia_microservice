import axiosClient from "../../../shared/api/axiosClient";
import type { Attachment, AttachmentCategory, AttachmentOwnerType } from "../types";

const BASE_URL = "/attachments";

const attachmentService = {
  list: (ownerType: AttachmentOwnerType, ownerId: string) =>
    axiosClient.get<Attachment[]>(BASE_URL, { params: { ownerType, ownerId } }),

  upload: (ownerType: AttachmentOwnerType, ownerId: string, category: AttachmentCategory, file: File) => {
    const form = new FormData();
    form.append("ownerId", ownerId);
    form.append("category", category);
    form.append("file", file);
    // The owner type travels in the query: the gateway routes on it (invoices are finance's).
    // No Content-Type here: the browser sets multipart/form-data with its boundary.
    return axiosClient.post<Attachment>(BASE_URL, form, { params: { ownerType } });
  },

  /**
   * The file goes through axios so the access token is sent; a plain link could not authenticate. The owner type
   * travels in the query, as for list and upload: the gateway routes on it (each service keeps its own attachments).
   */
  content: (ownerType: AttachmentOwnerType, id: string) =>
    axiosClient.get<Blob>(`${BASE_URL}/${id}/content`, { responseType: "blob", params: { ownerType } }),

  remove: (ownerType: AttachmentOwnerType, id: string) =>
    axiosClient.delete<void>(`${BASE_URL}/${id}`, { params: { ownerType } }),
};

export default attachmentService;
