package com.example.autoauction.diagnostic.media.web.dto;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

/** Full replacement of order - the list IS the new order, index 0 first. */
@Data
public class ReorderMediaRequest {

    @NotEmpty(message = "mediaIds обязателен")
    private List<Long> mediaIds;
}
