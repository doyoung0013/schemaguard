package com.shop;

@Entity
@Table(name = "products")
public class Product {
    @Id private Long id;
    @Column(name = "name") private String name;
}
