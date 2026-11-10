FROM rust:1.93-bookworm AS build
WORKDIR /app
COPY proto ./proto
COPY robot-agent ./robot-agent
WORKDIR /app/robot-agent
RUN cargo build --release --locked

FROM debian:bookworm-slim
RUN useradd --create-home --uid 10001 robot
COPY --from=build /app/robot-agent/target/release/robot-agent /usr/local/bin/robot-agent
USER robot
ENV GRPC_ADDR=0.0.0.0:50051
EXPOSE 50051
ENTRYPOINT ["robot-agent"]
