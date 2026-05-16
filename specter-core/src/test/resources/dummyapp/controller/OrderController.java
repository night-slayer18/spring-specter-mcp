package com.specter.core.dummyapp.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import com.specter.core.dummyapp.service.OrderServiceImpl;

import java.util.List;

@RestController
public class OrderController {

    private final OrderServiceImpl orderServiceImpl;

    public OrderController(OrderServiceImpl orderServiceImpl) {
        this.orderServiceImpl = orderServiceImpl;
    }

    @GetMapping("/api/orders")
    public List<Order> getOrders() {
        return orderServiceImpl.getAllOrders();
    }
}
