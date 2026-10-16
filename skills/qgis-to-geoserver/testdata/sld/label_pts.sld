<?xml version="1.0" encoding="UTF-8"?>
<StyledLayerDescriptor xmlns="http://www.opengis.net/sld" version="1.1.0" xmlns:ogc="http://www.opengis.net/ogc" xmlns:se="http://www.opengis.net/se" xmlns:xlink="http://www.w3.org/1999/xlink" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:schemaLocation="http://www.opengis.net/sld http://schemas.opengis.net/sld/1.1.0/StyledLayerDescriptor.xsd">
 <NamedLayer>
  <se:Name>label_pts</se:Name>
  <UserStyle>
   <se:Name>label_pts</se:Name>
   <se:FeatureTypeStyle>
    <se:Rule>
     <se:Name>Single symbol</se:Name>
     <se:PointSymbolizer>
      <se:Graphic>
       <se:Mark>
        <se:OnlineResource xlink:href="ttf://Sans" xlink:type="simple"/>
        <se:Format>ttf</se:Format>
        <se:MarkIndex>65</se:MarkIndex>
        <se:Fill>
         <se:SvgParameter name="fill">#6a3d9a</se:SvgParameter>
        </se:Fill>
       </se:Mark>
       <se:Size>36</se:Size>
      </se:Graphic>
     </se:PointSymbolizer>
    </se:Rule>
    <se:Rule>
     <se:TextSymbolizer>
      <se:Label>
       <ogc:Function name="upper">
        <ogc:PropertyName>nom</ogc:PropertyName>
       </ogc:Function>
      </se:Label>
      <se:Font>
       <se:SvgParameter name="font-family">Ubuntu Sans</se:SvgParameter>
       <se:SvgParameter name="font-size">13</se:SvgParameter>
      </se:Font>
      <se:LabelPlacement>
       <se:PointPlacement>
        <se:AnchorPoint>
         <se:AnchorPointX>0</se:AnchorPointX>
         <se:AnchorPointY>0.5</se:AnchorPointY>
        </se:AnchorPoint>
       </se:PointPlacement>
      </se:LabelPlacement>
      <se:Halo>
       <se:Radius>2.5</se:Radius>
       <se:Fill>
        <se:SvgParameter name="fill">#ffffff</se:SvgParameter>
       </se:Fill>
      </se:Halo>
      <se:Fill>
       <se:SvgParameter name="fill">#000000</se:SvgParameter>
      </se:Fill>
      <se:VendorOption name="maxDisplacement">1</se:VendorOption>
     </se:TextSymbolizer>
    </se:Rule>
   </se:FeatureTypeStyle>
  </UserStyle>
 </NamedLayer>
</StyledLayerDescriptor>
