package com.dsh.evals;

import com.dsh.evals.model.EvalConfig;
import com.dsh.evals.model.EvalDataset;
import com.dsh.evals.model.EvalReport;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 数据集与评测配置加载（JSON，复用 Jackson，无新依赖）。
 * 留出评分器规格仅存在于数据集文件，不进入 Agent 工作区。
 */
@Component
public class EvalDatasetLoader {

    private final ObjectMapper mapper = new ObjectMapper();

    public EvalDataset loadDataset(Path file) {
        try {
            return mapper.readValue(Files.readString(file, java.nio.charset.StandardCharsets.UTF_8),
                    EvalDataset.class);
        } catch (IOException e) {
            throw new UncheckedIOException("加载数据集失败: " + file, e);
        }
    }

    public EvalConfig loadConfig(Path file) {
        try {
            return mapper.readValue(Files.readString(file, java.nio.charset.StandardCharsets.UTF_8),
                    EvalConfig.class);
        } catch (IOException e) {
            throw new UncheckedIOException("加载评测配置失败: " + file, e);
        }
    }

    /** MA-11：离线读取历史评测报告 JSON，供 compare 模式使用。 */
    public EvalReport loadReport(Path file) {
        try {
            return mapper.readValue(Files.readString(file, java.nio.charset.StandardCharsets.UTF_8),
                    EvalReport.class);
        } catch (IOException e) {
            throw new UncheckedIOException("加载评测报告失败: " + file, e);
        }
    }
}
