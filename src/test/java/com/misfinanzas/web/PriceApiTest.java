package com.misfinanzas.web;

import com.misfinanzas.entity.PricePoint;
import com.misfinanzas.entity.TrackedProduct;
import com.misfinanzas.repository.PricePointRepository;
import com.misfinanzas.repository.TrackedProductRepository;
import com.misfinanzas.service.prices.PageFetcher;
import com.misfinanzas.service.prices.PriceReadException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The shop is a mock: tests never reach the internet. */
class PriceApiTest extends ApiTestBase {

    private static final String LINK = "https://chollostyle.es/product/57335";

    @MockitoBean
    PageFetcher fetcher;
    @Autowired
    TrackedProductRepository productRepository;
    @Autowired
    PricePointRepository pointRepository;

    private void shopSells(String price, String availability) {
        String html = "<html><head><script type=\"application/ld+json\">{\"@type\":\"Product\",\"name\":\"Club C 85\","
                + "\"offers\":{\"price\":\"" + price + "\",\"priceCurrency\":\"EUR\",\"availability\":\"" + availability
                + "\"}}</script></head></html>";
        doReturn(new PageFetcher.Page(URI.create(LINK), html)).when(fetcher).fetch(any()); // doReturn: fetch may be set to throw
    }

    private Long follow() throws Exception {
        mockMvc.perform(post("/api/prices").param("url", LINK).with(auth())).andExpect(status().isOk());
        return productRepository.findByUrl(LINK).orElseThrow().getId();
    }

    @Test
    void addingALinkReadsThePriceAndStartsTheHistory() throws Exception {
        shopSells("70.19", "InStock");

        mockMvc.perform(post("/api/prices").param("url", "  " + LINK + " ").param("target_price", "60").with(auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Club C 85"))
                .andExpect(jsonPath("$.store").value("chollostyle.es"))
                .andExpect(jsonPath("$.price").value(70.19))
                .andExpect(jsonPath("$.available").value(true))
                .andExpect(jsonPath("$.target_price").value(60.00))
                .andExpect(jsonPath("$.previous_price").doesNotExist())
                .andExpect(jsonPath("$.lowest_price").value(70.19));

        TrackedProduct saved = productRepository.findByUrl(LINK).orElseThrow();
        assertThat(pointRepository.findByProductIdOrderByCheckedAtAscIdAsc(saved.getId())).hasSize(1);
        mockMvc.perform(get("/api/prices").with(auth()))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].url").value(LINK));
    }

    @Test
    void aShopThatCantBeReadIsRefusedAndNothingIsSaved() throws Exception {
        when(fetcher.fetch(any())).thenThrow(new PriceReadException("Esta tienda no deja que un programa lea sus páginas"));

        mockMvc.perform(post("/api/prices").param("url", LINK).with(auth()))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value("Esta tienda no deja que un programa lea sus páginas"));

        assertThat(productRepository.count()).isZero();
    }

    @Test
    void aPageWithoutPriceIsRefused() throws Exception {
        when(fetcher.fetch(any())).thenReturn(new PageFetcher.Page(URI.create(LINK), "<html><title>Inicio</title></html>"));

        mockMvc.perform(post("/api/prices").param("url", LINK).with(auth()))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("no dice el precio")));
    }

    @Test
    void theSameLinkTwiceIsRefused() throws Exception {
        shopSells("70.19", "InStock");
        follow();

        mockMvc.perform(post("/api/prices").param("url", LINK).with(auth()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Ya estás siguiendo ese producto."));
    }

    @Test
    void amazonAndNonLinksAreRefusedWithoutVisitingAnything() throws Exception {
        mockMvc.perform(post("/api/prices").param("url", "https://www.amazon.es/dp/B0CZXYV8GT/").with(auth()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("Keepa")));
        mockMvc.perform(post("/api/prices").param("url", "zapatillas reebok").with(auth()))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/prices").param("url", " ").with(auth()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Pega el enlace del producto."));

        verify(fetcher, never()).fetch(any());
    }

    @Test
    void aPriceDropIsRecordedWithThePreviousPrice() throws Exception {
        shopSells("70.19", "InStock");
        Long id = follow();

        shopSells("55.00", "InStock");
        mockMvc.perform(post("/api/prices/{id}/check", id).with(auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.price").value(55.00))
                .andExpect(jsonPath("$.previous_price").value(70.19))
                .andExpect(jsonPath("$.changed_at").exists())
                .andExpect(jsonPath("$.lowest_price").value(55.00))
                .andExpect(jsonPath("$.highest_price").value(70.19));

        mockMvc.perform(get("/api/prices/{id}/history", id).with(auth()))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].price").value(70.19))
                .andExpect(jsonPath("$[1].price").value(55.00));
    }

    @Test
    void anUnchangedPriceAddsNoPointButRefreshesTheCheckTime() throws Exception {
        shopSells("70.19", "InStock");
        Long id = follow();
        var before = productRepository.findById(id).orElseThrow().getCheckedAt();

        Thread.sleep(5);
        mockMvc.perform(post("/api/prices/{id}/check", id).with(auth())).andExpect(status().isOk());

        assertThat(pointRepository.findByProductIdOrderByCheckedAtAscIdAsc(id)).hasSize(1);
        assertThat(productRepository.findById(id).orElseThrow().getCheckedAt()).isAfter(before);
    }

    @Test
    void goingOutOfStockIsAChangeEvenAtTheSamePrice() throws Exception {
        shopSells("70.19", "InStock");
        Long id = follow();

        shopSells("70.19", "OutOfStock");
        mockMvc.perform(post("/api/prices/{id}/check", id).with(auth()))
                .andExpect(jsonPath("$.available").value(false))
                .andExpect(jsonPath("$.previous_price").doesNotExist());

        assertThat(pointRepository.findByProductIdOrderByCheckedAtAscIdAsc(id)).extracting(PricePoint::getAvailable)
                .containsExactly(true, false);
    }

    @Test
    void comingBackInStockSaysWhen() throws Exception {
        shopSells("70.19", "InStock");
        Long id = follow();
        mockMvc.perform(get("/api/prices").with(auth()))
                .andExpect(jsonPath("$[0].restocked_at").doesNotExist()); // in stock from the start: nothing came back

        shopSells("70.19", "OutOfStock");
        mockMvc.perform(post("/api/prices/{id}/check", id).with(auth()))
                .andExpect(jsonPath("$.restocked_at").doesNotExist());

        shopSells("65.00", "InStock");
        mockMvc.perform(post("/api/prices/{id}/check", id).with(auth()))
                .andExpect(jsonPath("$.available").value(true))
                .andExpect(jsonPath("$.restocked_at").exists());
        var backAt = pointRepository.findByProductIdOrderByCheckedAtAscIdAsc(id).getLast().getCheckedAt();

        shopSells("60.00", "InStock"); // a later change while still in stock keeps the moment it came back
        mockMvc.perform(post("/api/prices/{id}/check", id).with(auth()))
                .andExpect(jsonPath("$.restocked_at").value(backAt.toString()));
    }

    @Test
    void aFailedCheckKeepsTheLastPriceAndSaysWhy() throws Exception {
        shopSells("70.19", "InStock");
        Long id = follow();

        when(fetcher.fetch(any())).thenThrow(new PriceReadException("No se ha podido conectar con la tienda"));
        mockMvc.perform(post("/api/prices/{id}/check", id).with(auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.price").value(70.19))
                .andExpect(jsonPath("$.last_error").value("No se ha podido conectar con la tienda"))
                .andExpect(jsonPath("$.last_error_at").exists());

        shopSells("70.19", "InStock");
        mockMvc.perform(post("/api/prices/{id}/check", id).with(auth()))
                .andExpect(jsonPath("$.last_error").doesNotExist());
    }

    @Test
    void theTargetPriceCanBeChangedAndRemoved() throws Exception {
        shopSells("70.19", "InStock");
        Long id = follow();

        mockMvc.perform(patch("/api/prices/{id}", id).param("target_price", "59.9").with(auth()))
                .andExpect(jsonPath("$.target_price").value(59.90));
        mockMvc.perform(patch("/api/prices/{id}", id).param("target_price", "").with(auth()))
                .andExpect(jsonPath("$.target_price").doesNotExist());
        mockMvc.perform(patch("/api/prices/{id}", id).param("target_price", "-3").with(auth()))
                .andExpect(status().isBadRequest());
        mockMvc.perform(patch("/api/prices/{id}", id).param("target_price", "mucho").with(auth()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void deletingAProductDeletesItsHistory() throws Exception {
        shopSells("70.19", "InStock");
        Long id = follow();

        mockMvc.perform(delete("/api/prices/{id}", id).with(auth())).andExpect(status().isOk());

        assertThat(productRepository.findById(id)).isEmpty();
        assertThat(pointRepository.findByProductIdOrderByCheckedAtAscIdAsc(id)).isEmpty();
    }

    @Test
    void anUnknownProductIs404() throws Exception {
        mockMvc.perform(post("/api/prices/{id}/check", 999999).with(auth())).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/prices/{id}/history", 999999).with(auth())).andExpect(status().isNotFound());
    }

    @Test
    void requiresLogin() throws Exception {
        mockMvc.perform(get("/api/prices")).andExpect(status().isUnauthorized());
    }
}
