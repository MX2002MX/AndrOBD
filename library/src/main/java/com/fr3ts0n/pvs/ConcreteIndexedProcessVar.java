package com.fr3ts0n.pvs;

public class ConcreteIndexedProcessVar extends IndexedProcessVar {

    @Override
    public String[] getFields() {
        return new String[] { "FID_DESCRIPT", "FID_VALUE", "FID_UNITS", "FID_PID" }; // Example field names
    }

    // Additional fields and methods specific to this subclass can be added here
}
