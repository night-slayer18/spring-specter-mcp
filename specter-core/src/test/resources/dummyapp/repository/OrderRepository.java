package com.specter.core.dummyapp.repository;

import com.specter.core.dummyapp.controller.Order;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;

@Repository
public class OrderRepository {

    private final List<Order> store = new ArrayList<>();

    public List<Order> findAll() {
        return new ArrayList<>(store);
    }

    public Order save(Order order) {
        store.add(order);
        return order;
    }
}
