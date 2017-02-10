package com.poyntapi.model;

/**
 * Created by dennis on 2/10/17.
 */
import co.poynt.api.model.Order;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public class OrdersResponse {
    public static class HATEOS {
        //region gettersSetters
        public String getHref() {
            return href;
        }

        public void setHref(String href) {
            this.href = href;
        }

        public String getRel() {
            return rel;
        }

        public void setRel(String rel) {
            this.rel = rel;
        }

        public String getMethod() {
            return method;
        }

        public void setMethod(String method) {
            this.method = method;
        }
        //endregion
        String href;
        String rel;
        String method;
        public HATEOS(){}

        @JsonCreator
        public HATEOS(@JsonProperty("href") String href, @JsonProperty("rel") String rel,@JsonProperty("method") String method) {
            this.href = href;
            this.rel = rel;
            this.method = method;
        }
        public String toString(){
            String retVal = "[";
            retVal += "href: " + href + ",";
            retVal += "rel: " + rel + ",";
            retVal += "method: " + method;
            retVal += "]";
            return retVal;
        }
    }

    public List<HATEOS> getLinks() {
        return links;
    }

    public void setLinks(List<HATEOS> links) {
        this.links = links;
    }

    List<HATEOS> links;

    public List<Order> getOrders() {
        return orders;
    }

    public void setOrders(List<Order> orders) {
        this.orders = orders;
    }

    List <Order> orders;

    public int getCount() {
        return count;
    }

    public void setCount(int count) {
        this.count = count;
    }

    int count;

    public OrdersResponse(){}

}