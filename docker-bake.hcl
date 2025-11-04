variable "image" {
  default = "alluxio"
}

variable "version" {
  default = "2.9.5"
}

variable "build" {
  default = 0
}

variable "tag" {
  default = "${version}.${build}"
}

variable "registry" {
  default = "localhost:5005"
}

target "alluxio" {
  context    = "integration/docker/"
  dockerfile = "Dockerfile"
  tags       = ["${registry}/${image}:${tag}"]

  platforms  = ["linux/amd64", "linux/arm64"]

  output = ["type=registry"]

  args = {
    BUILD      = "${build}"
  }
}

group "all" {
  targets = ["alluxio"]
}
