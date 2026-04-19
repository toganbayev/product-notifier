package dev.toganbayev.productmicroservice.service;

import dev.toganbayev.productmicroservice.service.dto.CreateProductDto;

import java.util.concurrent.ExecutionException;

// Business contract for product creation, handles synchronous creation and async event publishing
public interface ProductService {

    String createProduct(CreateProductDto createProductDto) throws ExecutionException, InterruptedException;
}
