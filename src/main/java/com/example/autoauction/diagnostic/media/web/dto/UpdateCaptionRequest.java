package com.example.autoauction.diagnostic.media.web.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class UpdateCaptionRequest {

    @Size(max = 1000, message = "caption не должен превышать 1000 символов")
    private String caption;
}
