package com.smartqa.platform.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 参考资料出处 VO —— 全团队接口契约（DEV_SPECIFICATION 4.2 冻结）。
 *
 * <p>⚠️ 这个类同时被两处使用，字段名一个都不能改：</p>
 * <ol>
 *   <li>成员 A 的 SSE {@code event: references} 数据包载荷；</li>
 *   <li>成员 B 的 {@code qa_record.grounding_references} JSON 列快照。</li>
 * </ol>
 *
 * <p>归口说明：本类由契约定义，放在 vo 包便于 A/B 共用。若成员 A 已在
 * {@code com.smartqa.platform.rag} 下建了同名类，请删除本文件、保留 A 的版本，
 * 但字段必须与本文件逐字符一致，否则前端出处抽屉会取不到值。</p>
 *
 * @author 契约（成员 A / 成员 B 共用）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "参考资料出处")
public class SseReferenceVO implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "课件 ID", example = "12")
    private Long docId;

    @Schema(description = "课件文件名", example = "第3章 内存管理.pdf")
    private String fileName;

    @Schema(description = "命中的分块序号", example = "14")
    private Integer chunkIndex;

    @Schema(description = "相关度得分 0~1", example = "0.88")
    private Double score;

    @Schema(description = "命中的原文片段")
    private String snippet;
}
