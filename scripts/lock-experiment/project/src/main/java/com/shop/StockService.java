package com.shop;

@Service
public class StockService {
    private StockRepository stockRepository;

    public void decrease(Long productId) { stockRepository.findByProductIdAndStatus(productId, "ON_SALE"); }
    public void adjust(Long id) { stockRepository.findById(id); }
    public Object onSale() { return stockRepository.findByStatus("ON_SALE"); }
    public void restock(Long productId) { stockRepository.lockAllOfProduct(productId); }
}
