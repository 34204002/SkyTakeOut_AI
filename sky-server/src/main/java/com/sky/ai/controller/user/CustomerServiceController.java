package com.sky.ai.controller.user;

import com.sky.annotation.RateLimit;
import com.sky.result.Result;
import com.sky.ai.service.CustomerServiceBotService;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@RestController("userCustomerServiceController")
@RequestMapping("/user/customer-service")
@Slf4j
@Tag(name = "智能客服接口")
public class CustomerServiceController {
    
    @Autowired
    private CustomerServiceBotService customerServiceBot;
    
    /**
     * 智能客服问答
     *
     * @param question 用户问题
     * @return AI回答
     */
    @GetMapping("/ask")
    @Operation(summary = "智能客服问答")
    @RateLimit(name = "customer-service-ask", capacity = 5, refillTokens = 5, refillSeconds = 1)
    @CircuitBreaker(name = "customer-service", fallbackMethod = "askFallback")
    public Result<String> ask(@RequestParam String question) {
        log.info("用户咨询: {}", question);
        String answer = customerServiceBot.answer(question);
        return Result.success(answer);
    }

    public Result<String> askFallback(String question, Exception e) {
        log.warn("客服熔断降级, question: {}, error: {}", question, e.getMessage());
        return Result.error("客服暂时不可用，请稍后再试");
    }
}
