package com.shop;

@Entity
@Table(name = "stocks")
public class Stock {
    @Id private Long id;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "product_id") private Product product;
    @Column(name = "status") private String status;
    @Column(name = "quantity") private int quantity;
}
