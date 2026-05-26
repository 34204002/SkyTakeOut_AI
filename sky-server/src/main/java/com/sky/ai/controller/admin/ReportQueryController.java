package com.sky.ai.controller.admin;

import com.sky.annotation.RateLimit;
import com.sky.dto.ai.ReportQueryDTO;
import com.sky.result.Result;
import com.sky.vo.ReportAnswerVO;
import com.sky.ai.service.ReportQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

/**
 * 管理端经营数据自然语言查询控制器
 */
@RestController("adminReportQueryController")
@RequestMapping("/admin/report")
@Slf4j
@Tag(name = "经营数据自然语言查询接口")
public class ReportQueryController {

    @Autowired
    private ReportQueryService reportQueryService;

    /**
     * 自然语言查询经营数据
     *
     * @param dto 查询请求参数
     * @return AI 回答 + 结构化数据
     */
    @PostMapping("/chat-query")
    @Operation(summary = "自然语言查询数据")
    @RateLimit(name = "report-query", capacity = 3, refillTokens = 3, refillSeconds = 1)
    public Result<ReportAnswerVO> chatQuery(@RequestBody ReportQueryDTO dto) {
        if (dto == null || dto.getQuestion() == null || dto.getQuestion().trim().isEmpty()) {
            return Result.error("请输入您的问题");
        }
        
        String question = dto.getQuestion().trim();
        log.info("收到自然语言查询: {}", question);
        
        ReportAnswerVO answer = reportQueryService.answerDataQuestion(question);
        
        return Result.success(answer);
    }
}
