package dev.lagless.desktop;

import dev.lagless.I18n;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;

/** Reads bounded, local files for explicit assistant requests. */
final class AssistantAttachments {
    private static final long MAX_IMAGE_BYTES = 4L * 1024 * 1024;
    private static final long MAX_PDF_BYTES = 12L * 1024 * 1024;
    private static final long MAX_TEXT_BYTES = 1_000_000;
    private static final int MAX_PDF_PAGES = 300;
    private static final int MAX_EXTRACTED_TEXT = 1_000_000;
    private static final Set<String> IMAGE_TYPES = Set.of("image/png", "image/jpeg", "image/gif", "image/webp");

    private AssistantAttachments() { }

    static AssistantPlugin.Attachment read(Path path) throws IOException {
        if (!Files.isRegularFile(path)) throw new IllegalArgumentException(I18n.t("附件不是普通文件：","Attachment is not a regular file: ") + path.getFileName());
        long size = Files.size(path);
        String filename = path.getFileName().toString();
        String lower = filename.toLowerCase(Locale.ROOT);
        String mime = Files.probeContentType(path);
        if (lower.endsWith(".png")) mime = "image/png";
        else if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) mime = "image/jpeg";
        else if (lower.endsWith(".gif")) mime = "image/gif";
        else if (lower.endsWith(".webp")) mime = "image/webp";

        if (mime != null && mime.startsWith("image/") && IMAGE_TYPES.contains(mime)) {
            if (size > MAX_IMAGE_BYTES) throw new IllegalArgumentException(I18n.t("图片单个不能超过 4 MiB：","Each image must be 4 MiB or smaller: ") + filename);
            return new AssistantPlugin.Attachment(filename, mime, Files.readAllBytes(path), null);
        }
        if (lower.endsWith(".pdf") || "application/pdf".equalsIgnoreCase(mime)) {
            if (size > MAX_PDF_BYTES) throw new IllegalArgumentException(I18n.t("PDF 文件不能超过 12 MiB：","PDF files must be 12 MiB or smaller: ") + filename);
            String extracted;
            try (var document = Loader.loadPDF(Files.readAllBytes(path))) {
                if (document.getNumberOfPages() > MAX_PDF_PAGES)
                    throw new IllegalArgumentException(I18n.t("PDF 最多支持 300 页：","PDF files can have at most 300 pages: ") + filename);
                PDFTextStripper stripper = new PDFTextStripper();
                extracted = stripper.getText(document);
            } catch (IllegalArgumentException error) {
                throw error;
            } catch (IOException error) {
                throw new IllegalArgumentException(I18n.t("无法读取 PDF（可能已加密或文件损坏）：","Cannot read the PDF (it may be encrypted or damaged): ") + filename + I18n.t("；","; ") + error.getMessage(), error);
            }
            if (extracted.isBlank())
                throw new IllegalArgumentException(I18n.t("PDF 中没有可提取的文字：","The PDF has no extractable text: ") + filename + I18n.t("。扫描版 PDF 暂不支持 OCR，请先转换成可搜索 PDF。 ",". Scanned PDFs need OCR, which is not supported yet; convert it to a searchable PDF first. "));
            if (extracted.length() > MAX_EXTRACTED_TEXT)
                extracted = extracted.substring(0, MAX_EXTRACTED_TEXT) + I18n.t("\n[PDF 文本已截断至 1,000,000 字符]","\n[PDF text truncated to 1,000,000 characters]");
            return new AssistantPlugin.Attachment(filename, "application/pdf", null, extracted);
        }

        if (size > MAX_TEXT_BYTES) throw new IllegalArgumentException(I18n.t("文本文件不能超过 1 MB：","Text files must be 1 MB or smaller: ") + filename);
        byte[] bytes = Files.readAllBytes(path);
        String text = new String(bytes, StandardCharsets.UTF_8);
        if (text.indexOf('\uFFFD') >= 0)
            throw new IllegalArgumentException(I18n.t("暂不支持该二进制文件，请添加图片、可搜索 PDF 或 UTF-8 文本文件：","This binary file is not supported; add an image, a searchable PDF or a UTF-8 text file: ") + filename);
        return new AssistantPlugin.Attachment(filename, lower.endsWith(".md") ? "text/markdown" : "text/plain", null, text);
    }
}
