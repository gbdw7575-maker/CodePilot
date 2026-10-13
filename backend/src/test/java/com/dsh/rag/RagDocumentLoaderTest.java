package com.dsh.rag;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RagDocumentLoaderTest {

    @TempDir
    Path tmp;

    @Test
    void splitByHeadingProducesOneChunkPerSection() throws IOException {
        Path md = tmp.resolve("s1.md");
        Files.writeString(md, """
                # 文件标题

                文件头部说明文字。

                ## 故事一：料理大战

                孙悟空和路飞在料理世界相遇。

                ## 故事二：密室事件

                辉夜和白银在古籍研究部。
                """);
        List<RagDocument> docs = new RagDocumentLoader(1000).load(tmp);
        assertEquals(3, docs.size());
        assertEquals("", docs.get(0).heading()); // # 标题之前的内容
        assertTrue(docs.get(0).text().contains("文件头部说明"));
        assertEquals("故事一：料理大战", docs.get(1).heading());
        assertTrue(docs.get(1).text().contains("孙悟空"));
        assertEquals("故事二：密室事件", docs.get(2).heading());
        assertTrue(docs.get(2).text().contains("辉夜"));
    }

    @Test
    void longSectionSplitByParagraph() throws IOException {
        StringBuilder longPara = new StringBuilder();
        for (int i = 0; i < 50; i++) {
            longPara.append("这是一段较长的内容用于测试按段落切分。");
        }
        Path md = tmp.resolve("long.md");
        Files.writeString(md, "## 长故事\n\n" + longPara + "\n\n第二段短内容。");
        List<RagDocument> docs = new RagDocumentLoader(100).load(tmp);
        assertTrue(docs.size() > 1);
        // 所有 chunk 共享同一 heading
        for (RagDocument d : docs) {
            assertEquals("长故事", d.heading());
        }
        // 最后一个 chunk 包含第二段
        assertTrue(docs.get(docs.size() - 1).text().contains("第二段短内容"));
    }

    @Test
    void emptyFileProducesNoChunks() throws IOException {
        Path md = tmp.resolve("empty.md");
        Files.writeString(md, "");
        List<RagDocument> docs = new RagDocumentLoader(1000).load(tmp);
        assertTrue(docs.isEmpty());
    }

    @Test
    void chunkIdUniquePerFile() throws IOException {
        Path md = tmp.resolve("s.md");
        Files.writeString(md, "## a\n内容一\n## b\n内容二\n## c\n内容三");
        List<RagDocument> docs = new RagDocumentLoader(1000).load(tmp);
        assertEquals(3, docs.size());
        assertEquals("s.md#0", docs.get(0).id());
        assertEquals("s.md#1", docs.get(1).id());
        assertEquals("s.md#2", docs.get(2).id());
        assertEquals(0, docs.get(0).chunkIndex());
        assertEquals(1, docs.get(1).chunkIndex());
    }

    @Test
    void nonExistentDirReturnsEmpty() throws IOException {
        List<RagDocument> docs = new RagDocumentLoader(1000).load(tmp.resolve("nope"));
        assertTrue(docs.isEmpty());
    }

    @Test
    void onlyMdFilesLoaded() throws IOException {
        Files.writeString(tmp.resolve("a.md"), "## 标题\n内容");
        Files.writeString(tmp.resolve("b.txt"), "不是 md");
        List<RagDocument> docs = new RagDocumentLoader(1000).load(tmp);
        assertEquals(1, docs.size());
        assertEquals("a.md", docs.get(0).sourcePath());
    }
}
